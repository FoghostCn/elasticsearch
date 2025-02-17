/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.datastreams.lifecycle;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ResultDeduplicator;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.forcemerge.ForceMergeRequest;
import org.elasticsearch.action.admin.indices.forcemerge.ForceMergeResponse;
import org.elasticsearch.action.admin.indices.readonly.AddIndexBlockRequest;
import org.elasticsearch.action.admin.indices.readonly.AddIndexBlockResponse;
import org.elasticsearch.action.admin.indices.rollover.RolloverConfiguration;
import org.elasticsearch.action.admin.indices.rollover.RolloverRequest;
import org.elasticsearch.action.admin.indices.rollover.RolloverResponse;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.elasticsearch.action.downsample.DownsampleAction;
import org.elasticsearch.action.downsample.DownsampleConfig;
import org.elasticsearch.action.support.DefaultShardOperationFailedException;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.ClusterStateTaskListener;
import org.elasticsearch.cluster.SimpleBatchedExecutor;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.cluster.metadata.DataStreamLifecycle;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.cluster.service.MasterServiceTaskQueue;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.component.Lifecycle;
import org.elasticsearch.common.scheduler.SchedulerEngine;
import org.elasticsearch.common.scheduler.TimeValueSchedule;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.Strings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.datastreams.lifecycle.downsampling.ReplaceBackingWithDownsampleIndexExecutor;
import org.elasticsearch.datastreams.lifecycle.downsampling.ReplaceSourceWithDownsampleIndexTask;
import org.elasticsearch.gateway.GatewayService;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.MergePolicyConfig;
import org.elasticsearch.snapshots.SnapshotInProgressException;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportRequest;

import java.io.Closeable;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import static org.elasticsearch.cluster.metadata.IndexMetadata.APIBlock.WRITE;
import static org.elasticsearch.cluster.metadata.IndexMetadata.DownsampleTaskStatus.STARTED;
import static org.elasticsearch.cluster.metadata.IndexMetadata.DownsampleTaskStatus.SUCCESS;
import static org.elasticsearch.cluster.metadata.IndexMetadata.INDEX_DOWNSAMPLE_STATUS;
import static org.elasticsearch.datastreams.DataStreamsPlugin.LIFECYCLE_CUSTOM_INDEX_METADATA_KEY;

/**
 * This service will implement the needed actions (e.g. rollover, retention) to manage the data streams with a data stream lifecycle
 * configured. It runs on the master node and it schedules a job according to the configured
 * {@link DataStreamLifecycleService#DATA_STREAM_LIFECYCLE_POLL_INTERVAL_SETTING}.
 */
public class DataStreamLifecycleService implements ClusterStateListener, Closeable, SchedulerEngine.Listener {

    public static final String DATA_STREAM_LIFECYCLE_POLL_INTERVAL = "data_streams.lifecycle.poll_interval";
    public static final Setting<TimeValue> DATA_STREAM_LIFECYCLE_POLL_INTERVAL_SETTING = Setting.timeSetting(
        DATA_STREAM_LIFECYCLE_POLL_INTERVAL,
        TimeValue.timeValueMinutes(5),
        TimeValue.timeValueSeconds(1),
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );
    public static final ByteSizeValue ONE_HUNDRED_MB = ByteSizeValue.ofMb(100);

    public static final int TARGET_MERGE_FACTOR_VALUE = 16;

    public static final Setting<Integer> DATA_STREAM_MERGE_POLICY_TARGET_FACTOR_SETTING = Setting.intSetting(
        "data_streams.lifecycle.target.merge.policy.merge_factor",
        TARGET_MERGE_FACTOR_VALUE,
        2,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<ByteSizeValue> DATA_STREAM_MERGE_POLICY_TARGET_FLOOR_SEGMENT_SETTING = Setting.byteSizeSetting(
        "data_streams.lifecycle.target.merge.policy.floor_segment",
        ONE_HUNDRED_MB,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );
    public static final String DOWNSAMPLED_INDEX_PREFIX = "downsample-";

    private static final Logger logger = LogManager.getLogger(DataStreamLifecycleService.class);
    /**
     * Name constant for the job that schedules the data stream lifecycle
     */
    private static final String LIFECYCLE_JOB_NAME = "data_stream_lifecycle";
    /*
     * This is the key for data stream lifecycle related custom index metadata.
     */
    public static final String FORCE_MERGE_COMPLETED_TIMESTAMP_METADATA_KEY = "force_merge_completed_timestamp";
    private final Settings settings;
    private final Client client;
    private final ClusterService clusterService;
    private final ThreadPool threadPool;
    final ResultDeduplicator<TransportRequest, Void> transportActionsDeduplicator;
    final ResultDeduplicator<ClusterStateTaskListener, Void> clusterStateChangesDeduplicator;
    private final LongSupplier nowSupplier;
    private final Clock clock;
    private final DataStreamLifecycleErrorStore errorStore;
    private volatile boolean isMaster = false;
    private volatile TimeValue pollInterval;
    private volatile RolloverConfiguration rolloverConfiguration;
    private SchedulerEngine.Job scheduledJob;
    private final SetOnce<SchedulerEngine> scheduler = new SetOnce<>();
    private final MasterServiceTaskQueue<UpdateForceMergeCompleteTask> forceMergeClusterStateUpdateTaskQueue;
    private final MasterServiceTaskQueue<ReplaceSourceWithDownsampleIndexTask> swapSourceWithDownsampleIndexQueue;
    private volatile ByteSizeValue targetMergePolicyFloorSegment;
    private volatile int targetMergePolicyFactor;

    private static final SimpleBatchedExecutor<UpdateForceMergeCompleteTask, Void> FORCE_MERGE_STATE_UPDATE_TASK_EXECUTOR =
        new SimpleBatchedExecutor<>() {
            @Override
            public Tuple<ClusterState, Void> executeTask(UpdateForceMergeCompleteTask task, ClusterState clusterState) throws Exception {
                return Tuple.tuple(task.execute(clusterState), null);
            }

            @Override
            public void taskSucceeded(UpdateForceMergeCompleteTask task, Void unused) {
                logger.trace("Updated cluster state for force merge of index [{}]", task.targetIndex);
                task.listener.onResponse(null);
            }
        };

    public DataStreamLifecycleService(
        Settings settings,
        Client client,
        ClusterService clusterService,
        Clock clock,
        ThreadPool threadPool,
        LongSupplier nowSupplier,
        DataStreamLifecycleErrorStore errorStore
    ) {
        this.settings = settings;
        this.client = client;
        this.clusterService = clusterService;
        this.clock = clock;
        this.threadPool = threadPool;
        this.transportActionsDeduplicator = new ResultDeduplicator<>(threadPool.getThreadContext());
        this.clusterStateChangesDeduplicator = new ResultDeduplicator<>(threadPool.getThreadContext());
        this.nowSupplier = nowSupplier;
        this.errorStore = errorStore;
        this.scheduledJob = null;
        this.pollInterval = DATA_STREAM_LIFECYCLE_POLL_INTERVAL_SETTING.get(settings);
        this.targetMergePolicyFloorSegment = DATA_STREAM_MERGE_POLICY_TARGET_FLOOR_SEGMENT_SETTING.get(settings);
        this.targetMergePolicyFactor = DATA_STREAM_MERGE_POLICY_TARGET_FACTOR_SETTING.get(settings);
        this.rolloverConfiguration = clusterService.getClusterSettings()
            .get(DataStreamLifecycle.CLUSTER_LIFECYCLE_DEFAULT_ROLLOVER_SETTING);
        this.forceMergeClusterStateUpdateTaskQueue = clusterService.createTaskQueue(
            "data-stream-lifecycle-forcemerge-state-update",
            Priority.LOW,
            FORCE_MERGE_STATE_UPDATE_TASK_EXECUTOR
        );
        this.swapSourceWithDownsampleIndexQueue = clusterService.createTaskQueue(
            "data-stream-lifecycle-swap-source-with-downsample",
            Priority.NORMAL,
            new ReplaceBackingWithDownsampleIndexExecutor(client)
        );
    }

    /**
     * Initializer method to avoid the publication of a self reference in the constructor.
     */
    public void init() {
        clusterService.addListener(this);
        clusterService.getClusterSettings()
            .addSettingsUpdateConsumer(DATA_STREAM_LIFECYCLE_POLL_INTERVAL_SETTING, this::updatePollInterval);
        clusterService.getClusterSettings()
            .addSettingsUpdateConsumer(DataStreamLifecycle.CLUSTER_LIFECYCLE_DEFAULT_ROLLOVER_SETTING, this::updateRolloverConfiguration);
        clusterService.getClusterSettings()
            .addSettingsUpdateConsumer(DATA_STREAM_MERGE_POLICY_TARGET_FACTOR_SETTING, this::updateMergePolicyFactor);
        clusterService.getClusterSettings()
            .addSettingsUpdateConsumer(DATA_STREAM_MERGE_POLICY_TARGET_FLOOR_SEGMENT_SETTING, this::updateMergePolicyFloorSegment);
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        // wait for the cluster state to be recovered
        if (event.state().blocks().hasGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)) {
            return;
        }

        final boolean prevIsMaster = this.isMaster;
        if (prevIsMaster != event.localNodeMaster()) {
            this.isMaster = event.localNodeMaster();
            if (this.isMaster) {
                // we weren't the master, and now we are
                maybeScheduleJob();
            } else {
                // we were the master, and now we aren't
                cancelJob();
                // clear the deduplicator on master failover so we could re-send the requests in case we're re-elected
                transportActionsDeduplicator.clear();
                errorStore.clearStore();
            }
        }
    }

    @Override
    public void close() {
        SchedulerEngine engine = scheduler.get();
        if (engine != null) {
            engine.stop();
        }
        errorStore.clearStore();
    }

    @Override
    public void triggered(SchedulerEngine.Event event) {
        if (event.getJobName().equals(LIFECYCLE_JOB_NAME)) {
            if (this.isMaster) {
                logger.trace(
                    "Data stream lifecycle job triggered: {}, {}, {}",
                    event.getJobName(),
                    event.getScheduledTime(),
                    event.getTriggeredTime()
                );
                run(clusterService.state());
            }
        }
    }

    /**
     * Iterates over the data stream lifecycle managed data streams and executes the needed operations
     * to satisfy the configured {@link DataStreamLifecycle}.
     */
    // default visibility for testing purposes
    void run(ClusterState state) {
        int affectedIndices = 0;
        int affectedDataStreams = 0;
        for (DataStream dataStream : state.metadata().dataStreams().values()) {
            clearErrorStoreForUnmanagedIndices(dataStream);
            if (dataStream.getLifecycle() == null) {
                continue;
            }

            /*
             * This is the pre-rollover write index. It may or may not be the write index after maybeExecuteRollover has executed, depending
             * on rollover criteria. We're keeping a reference to it because regardless of whether it's rolled over or not we want to
             * exclude it from force merging later in this data stream lifecycle run.
             */
            Index currentRunWriteIndex = dataStream.getWriteIndex();
            try {
                maybeExecuteRollover(state, dataStream);
            } catch (Exception e) {
                logger.error(
                    () -> String.format(Locale.ROOT, "Data stream lifecycle failed to rollover data stream [%s]", dataStream.getName()),
                    e
                );
                DataStream latestDataStream = clusterService.state().metadata().dataStreams().get(dataStream.getName());
                if (latestDataStream != null) {
                    if (latestDataStream.getWriteIndex().getName().equals(currentRunWriteIndex.getName())) {
                        // data stream has not been rolled over in the meantime so record the error against the write index we
                        // attempted the rollover
                        errorStore.recordError(currentRunWriteIndex.getName(), e);
                    }
                }
            }
            Set<Index> indicesBeingRemoved;
            try {
                indicesBeingRemoved = maybeExecuteRetention(state, dataStream);
            } catch (Exception e) {
                indicesBeingRemoved = Set.of();
                // individual index errors would be reported via the API action listener for every delete call
                // we could potentially record errors at a data stream level and expose it via the _data_stream API?
                logger.error(
                    () -> String.format(
                        Locale.ROOT,
                        "Data stream lifecycle failed to execute retention for data stream [%s]",
                        dataStream.getName()
                    ),
                    e
                );
            }

            // the following indices should not be considered for the remainder of this service run:
            // 1) the write index as it's still getting writes and we'll have to roll it over when the conditions are met
            // 2) we exclude any indices that we're in the process of deleting because they'll be gone soon anyway
            Set<Index> indicesToExcludeForRemainingRun = new HashSet<>();
            indicesToExcludeForRemainingRun.add(currentRunWriteIndex);
            indicesToExcludeForRemainingRun.addAll(indicesBeingRemoved);

            try {
                indicesToExcludeForRemainingRun.addAll(
                    maybeExecuteForceMerge(state, getTargetIndices(dataStream, indicesToExcludeForRemainingRun, state.metadata()::index))
                );
            } catch (Exception e) {
                logger.error(
                    () -> String.format(
                        Locale.ROOT,
                        "Data stream lifecycle failed to execute force merge for data stream [%s]",
                        dataStream.getName()
                    ),
                    e
                );
            }

            try {
                indicesToExcludeForRemainingRun.addAll(
                    maybeExecuteDownsampling(
                        state,
                        dataStream,
                        getTargetIndices(dataStream, indicesToExcludeForRemainingRun, state.metadata()::index)
                    )
                );
            } catch (Exception e) {
                logger.error(
                    () -> String.format(
                        Locale.ROOT,
                        "Data stream lifecycle failed to execute downsampling for data stream [%s]",
                        dataStream.getName()
                    ),
                    e
                );
            }

            affectedIndices += indicesToExcludeForRemainingRun.size();
            affectedDataStreams++;
        }
        logger.trace(
            "Data stream lifecycle service performed operations on [{}] indices, part of [{}] data streams",
            affectedIndices,
            affectedDataStreams
        );
    }

    /**
     * Data stream lifecycle supports configuring multiple rounds of downsampling for each managed index. When attempting to execute
     * downsampling we iterate through the ordered rounds of downsampling that match an index (ordered ascending according to the `after`
     * configuration) and try to figure out:
     * - if we started downsampling for an earlier round and is in progress, in which case we need to wait for it to complete
     * - if we started downsampling for an earlier round and it's finished but the downsampling index is not part of the data stream, in
     * which case we need to replace the backing index with the downsampling index and delete the backing index
     * - if we don't have any early rounds started or to add to the data stream, start downsampling the last matching round
     *
     * Note that the first time an index has a matching downsampling round we first mark it as read-only.
     *
     * Returns a set of indices that now have in-flight operations triggered by downsampling (it could be marking them as read-only,
     * replacing an index in the data stream, deleting a source index, or downsampling itself) so these indices can be skipped in case
     * there are other operations to be executed by the data stream lifecycle after downsampling.
     */
    Set<Index> maybeExecuteDownsampling(ClusterState state, DataStream dataStream, List<Index> targetIndices) {
        Set<Index> affectedIndices = new HashSet<>();
        Metadata metadata = state.metadata();
        for (Index index : targetIndices) {
            IndexMetadata backingIndexMeta = metadata.index(index);
            assert backingIndexMeta != null : "the data stream backing indices must exist";
            List<DataStreamLifecycle.Downsampling.Round> downsamplingRounds = dataStream.getDownsamplingRoundsFor(
                index,
                metadata::index,
                nowSupplier
            );
            if (downsamplingRounds.isEmpty()) {
                continue;
            }

            String indexName = index.getName();
            IndexMetadata.DownsampleTaskStatus backingIndexDownsamplingStatus = INDEX_DOWNSAMPLE_STATUS.get(backingIndexMeta.getSettings());
            String downsamplingSourceIndex = IndexMetadata.INDEX_DOWNSAMPLE_SOURCE_NAME.get(backingIndexMeta.getSettings());

            // if the current index is not a downsample we want to mark the index as read-only before proceeding with downsampling
            if (org.elasticsearch.common.Strings.hasText(downsamplingSourceIndex) == false
                && state.blocks().indexBlocked(ClusterBlockLevel.WRITE, indexName) == false) {
                affectedIndices.add(index);
                addIndexBlockOnce(indexName);
            } else if (org.elasticsearch.common.Strings.hasText(downsamplingSourceIndex)
                && backingIndexDownsamplingStatus.equals(SUCCESS)) {
                    // if the backing index is a downsample index itself, let's check if its source index still exists as we must delete it
                    IndexMetadata downsampleSourceIndex = metadata.index(downsamplingSourceIndex);
                    if (downsampleSourceIndex != null) {
                        // we mark the backing index as affected as we don't want subsequent operations that might change its state to
                        // be performed, as we might lose the way to identify that we must delete its replacement source index
                        affectedIndices.add(index);
                        // delete downsampling source index (that's not part of the data stream anymore) before doing any more
                        // downsampling
                        deleteIndexOnce(downsamplingSourceIndex, "replacement with its downsampled index in the data stream");
                    }
                }

            if (affectedIndices.contains(index) == false) {
                // we're not performing any operation for this index which means that it:
                // - has matching downsample rounds
                // - is read-only
                // So let's wait for an in-progress downsampling operation to succeed or trigger the last matching round
                affectedIndices.addAll(waitForInProgressOrTriggerDownsampling(dataStream, backingIndexMeta, downsamplingRounds, metadata));
            }
        }

        return affectedIndices;
    }

    /**
     * Iterate over the matching downsampling rounds for the backing index (if any) and either wait for an early round to complete,
     * add an early completed downsampling round to the data stream, or otherwise trigger the last matching downsampling round.
     *
     * Returns the indices for which we triggered an action/operation.
     */
    private Set<Index> waitForInProgressOrTriggerDownsampling(
        DataStream dataStream,
        IndexMetadata backingIndex,
        List<DataStreamLifecycle.Downsampling.Round> downsamplingRounds,
        Metadata metadata
    ) {
        assert dataStream.getIndices().contains(backingIndex.getIndex())
            : "the provided backing index must be part of data stream:" + dataStream.getName();
        assert downsamplingRounds.isEmpty() == false : "the index should be managed and have matching downsampling rounds";
        Set<Index> affectedIndices = new HashSet<>();
        DataStreamLifecycle.Downsampling.Round lastRound = downsamplingRounds.get(downsamplingRounds.size() - 1);

        Index index = backingIndex.getIndex();
        String indexName = index.getName();
        for (DataStreamLifecycle.Downsampling.Round round : downsamplingRounds) {
            // the downsample index name for each round is deterministic
            String downsampleIndexName = DownsampleConfig.generateDownsampleIndexName(
                DOWNSAMPLED_INDEX_PREFIX,
                backingIndex,
                round.config().getFixedInterval()
            );
            IndexMetadata targetDownsampleIndexMeta = metadata.index(downsampleIndexName);
            boolean targetDownsampleIndexExists = targetDownsampleIndexMeta != null;

            if (targetDownsampleIndexExists) {
                Set<Index> downsamplingNotComplete = evaluateDownsampleStatus(
                    dataStream,
                    INDEX_DOWNSAMPLE_STATUS.get(targetDownsampleIndexMeta.getSettings()),
                    round,
                    lastRound,
                    index,
                    targetDownsampleIndexMeta.getIndex()
                );
                if (downsamplingNotComplete.isEmpty() == false) {
                    affectedIndices.addAll(downsamplingNotComplete);
                    break;
                }
            } else {
                if (round.equals(lastRound)) {
                    // no maintenance needed for previously started downsampling actions and we are on the last matching round so it's time
                    // to kick off downsampling
                    affectedIndices.add(index);
                    downsampleIndexOnce(round, indexName, downsampleIndexName);
                }
            }
        }
        return affectedIndices;
    }

    /**
     * Issues a request downsample the source index to the downsample index for the specified round.
     */
    private void downsampleIndexOnce(DataStreamLifecycle.Downsampling.Round round, String sourceIndex, String downsampleIndexName) {
        DownsampleAction.Request request = new DownsampleAction.Request(sourceIndex, downsampleIndexName, null, round.config());
        transportActionsDeduplicator.executeOnce(
            request,
            new ErrorRecordingActionListener(
                sourceIndex,
                errorStore,
                Strings.format(
                    "Data stream lifecycle encountered an error trying to downsample index [%s]. Data stream lifecycle will "
                        + "attempt to downsample the index on its next run.",
                    sourceIndex
                )
            ),
            (req, reqListener) -> downsampleIndex(request, reqListener)
        );
    }

    /**
     * Checks the status of the downsampling operations for the provided backing index and its corresponding downsample index.
     * Depending on the status, we'll either error (if it's UNKNOWN and we've reached the last round), wait for it to complete (if it's
     * STARTED), or replace the backing index with the downsample index in the data stream (if the status is SUCCESS).
     */
    private Set<Index> evaluateDownsampleStatus(
        DataStream dataStream,
        IndexMetadata.DownsampleTaskStatus downsampleStatus,
        DataStreamLifecycle.Downsampling.Round currentRound,
        DataStreamLifecycle.Downsampling.Round lastRound,
        Index backingIndex,
        Index downsampleIndex
    ) {
        Set<Index> affectedIndices = new HashSet<>();
        String indexName = backingIndex.getName();
        String downsampleIndexName = downsampleIndex.getName();
        return switch (downsampleStatus) {
            case UNKNOWN -> {
                if (currentRound.equals(lastRound)) {
                    // target downsampling index exists and is not a downsampling index (name clash?)
                    // we fail now but perhaps we should just randomise the name?
                    String previousError = errorStore.getError(indexName);

                    errorStore.recordError(indexName, new ResourceAlreadyExistsException(downsampleIndexName));
                    // To avoid spamming our logs, we only want to log the error once.
                    if (previousError == null || previousError.equals(errorStore.getError(indexName)) == false) {
                        logger.error(
                            "Data stream lifecycle service is unable to downsample backing index [{}] for data stream [{}] and "
                                + "donwsampling round [{}] because the target downsample index [{}] already exists",
                            indexName,
                            dataStream.getName(),
                            currentRound,
                            downsampleIndexName
                        );
                    }
                }
                yield affectedIndices;
            }
            case STARTED -> {
                // we'll wait for this round to complete
                // TODO add support for cancelling a current in-progress operation if another, later, round matches
                logger.trace(
                    "Data stream lifecycle service waits for index [{}] to be downsampled. Current status is [{}] and the "
                        + "downsample index name is [{}]",
                    indexName,
                    STARTED,
                    downsampleIndexName
                );
                // this request here might seem weird, but hear me out:
                // if we triggered a downsample operation, and then had a master failover (so DSL starts from scratch)
                // we can't really find out if the downsampling persistent task failed (if it was successful, no worries, the next case
                // SUCCESS branch will catch it and we will cruise forward)
                // if the downsampling persistent task failed, we will find out only via re-issuing the downsample request (and we will
                // continue to re-issue the request until we get SUCCESS)

                // NOTE that the downsample request is made through the deduplicator so it will only really be executed if
                // there isn't one already in-flight. This can happen if a previous request timed-out, failed, or there was a
                // master failover and data stream lifecycle needed to restart
                downsampleIndexOnce(currentRound, indexName, downsampleIndexName);
                affectedIndices.add(backingIndex);
                yield affectedIndices;
            }
            case SUCCESS -> {
                if (dataStream.getIndices().contains(downsampleIndex) == false) {
                    // at this point the source index is part of the data stream and the downsample index is complete but not
                    // part of the data stream. we need to replace the source index with the downsample index in the data stream
                    affectedIndices.add(backingIndex);
                    replaceBackingIndexWithDownsampleIndexOnce(dataStream, indexName, downsampleIndexName);
                }
                yield affectedIndices;
            }
        };
    }

    /**
     * Issues a request to replace the backing index with the downsample index through the cluster state changes deduplicator.
     */
    private void replaceBackingIndexWithDownsampleIndexOnce(DataStream dataStream, String backingIndexName, String downsampleIndexName) {
        clusterStateChangesDeduplicator.executeOnce(
            new ReplaceSourceWithDownsampleIndexTask(dataStream.getName(), backingIndexName, downsampleIndexName, null),
            new ErrorRecordingActionListener(
                backingIndexName,
                errorStore,
                Strings.format(
                    "Data stream lifecycle encountered an error trying to replace index [%s] with index [%s] in data stream [%s]",
                    backingIndexName,
                    downsampleIndexName,
                    dataStream
                )
            ),
            (req, reqListener) -> {
                logger.trace(
                    "Data stream lifecycle issues request to replace index [{}] with index [{}] in data stream [{}]",
                    backingIndexName,
                    downsampleIndexName,
                    dataStream
                );
                swapSourceWithDownsampleIndexQueue.submitTask(
                    "data-stream-lifecycle-replace-source[" + backingIndexName + "]-with-[" + downsampleIndexName + "]",
                    new ReplaceSourceWithDownsampleIndexTask(dataStream.getName(), backingIndexName, downsampleIndexName, reqListener),
                    null
                );
            }
        );
    }

    /**
     * Issues a request to delete the provided index through the transport action deduplicator.
     */
    private void deleteIndexOnce(String indexName, String reason) {
        DeleteIndexRequest deleteIndexRequest = new DeleteIndexRequest(indexName).masterNodeTimeout(TimeValue.MAX_VALUE);
        transportActionsDeduplicator.executeOnce(
            deleteIndexRequest,
            new ErrorRecordingActionListener(
                indexName,
                errorStore,
                Strings.format("Data stream lifecycle encountered an error trying to delete index [%s]", indexName)
            ),
            (req, reqListener) -> deleteIndex(deleteIndexRequest, reason, reqListener)
        );
    }

    /**
     * Issues a request to add a WRITE index block for the provided index through the transport action deduplicator.
     */
    private void addIndexBlockOnce(String indexName) {
        AddIndexBlockRequest addIndexBlockRequest = new AddIndexBlockRequest(WRITE, indexName).masterNodeTimeout(TimeValue.MAX_VALUE);
        transportActionsDeduplicator.executeOnce(
            addIndexBlockRequest,
            new ErrorRecordingActionListener(
                indexName,
                errorStore,
                Strings.format("Data stream lifecycle service encountered an error trying to mark index [%s] as readonly", indexName)
            ),
            (req, reqListener) -> addIndexBlock(addIndexBlockRequest, reqListener)
        );
    }

    /**
     * Returns the data stream lifecycle managed indices that are not part of the set of indices to exclude.
     */
    private static List<Index> getTargetIndices(
        DataStream dataStream,
        Set<Index> indicesToExcludeForRemainingRun,
        Function<String, IndexMetadata> indexMetadataSupplier
    ) {
        return dataStream.getIndices()
            .stream()
            .filter(
                index -> dataStream.isIndexManagedByDataStreamLifecycle(index, indexMetadataSupplier)
                    && indicesToExcludeForRemainingRun.contains(index) == false
            )
            .toList();
    }

    /**
     * This clears the error store for the case where a data stream or some backing indices were managed by data stream lifecycle, failed in
     * their lifecycle execution, and then they were not managed by the data stream lifecycle (maybe they were switched to ILM).
     */
    private void clearErrorStoreForUnmanagedIndices(DataStream dataStream) {
        Metadata metadata = clusterService.state().metadata();
        for (String indexName : errorStore.getAllIndices()) {
            IndexMetadata indexMeta = metadata.index(indexName);
            if (indexMeta == null) {
                errorStore.clearRecordedError(indexName);
            } else if (dataStream.isIndexManagedByDataStreamLifecycle(indexMeta.getIndex(), metadata::index) == false) {
                errorStore.clearRecordedError(indexName);
            }
        }
    }

    private void maybeExecuteRollover(ClusterState state, DataStream dataStream) {
        Index writeIndex = dataStream.getWriteIndex();
        if (dataStream.isIndexManagedByDataStreamLifecycle(writeIndex, state.metadata()::index)) {
            RolloverRequest rolloverRequest = getDefaultRolloverRequest(
                rolloverConfiguration,
                dataStream.getName(),
                dataStream.getLifecycle().getEffectiveDataRetention()
            );
            transportActionsDeduplicator.executeOnce(
                rolloverRequest,
                new ErrorRecordingActionListener(
                    writeIndex.getName(),
                    errorStore,
                    Strings.format("Data stream lifecycle encountered an error trying to rollover data steam [%s]", dataStream.getName())
                ),
                (req, reqListener) -> rolloverDataStream(writeIndex.getName(), rolloverRequest, reqListener)
            );
        }
    }

    /**
     * This method sends requests to delete any indices in the datastream that exceed its retention policy. It returns the set of indices
     * it has sent delete requests for.
     * @param state The cluster state from which to get index metadata
     * @param dataStream The datastream
     * @return The set of indices that delete requests have been sent for
     */
    private Set<Index> maybeExecuteRetention(ClusterState state, DataStream dataStream) {
        TimeValue retention = getRetentionConfiguration(dataStream);
        Set<Index> indicesToBeRemoved = new HashSet<>();
        if (retention != null) {
            Metadata metadata = state.metadata();
            List<Index> backingIndicesOlderThanRetention = dataStream.getIndicesPastRetention(metadata::index, nowSupplier);

            for (Index index : backingIndicesOlderThanRetention) {
                indicesToBeRemoved.add(index);
                IndexMetadata backingIndex = metadata.index(index);
                assert backingIndex != null : "the data stream backing indices must exist";

                // there's an opportunity here to batch the delete requests (i.e. delete 100 indices / request)
                // let's start simple and reevaluate
                String indexName = backingIndex.getIndex().getName();
                deleteIndexOnce(indexName, "the lapsed [" + retention + "] retention period");
            }
        }
        return indicesToBeRemoved;
    }

    /*
     * This method force merges the given indices in the datastream. It writes a timestamp in the cluster state upon completion of the
     * force merge.
     */
    private Set<Index> maybeExecuteForceMerge(ClusterState state, List<Index> indices) {
        Metadata metadata = state.metadata();
        Set<Index> affectedIndices = new HashSet<>();
        for (Index index : indices) {
            IndexMetadata backingIndex = metadata.index(index);
            assert backingIndex != null : "the data stream backing indices must exist";
            String indexName = index.getName();
            boolean alreadyForceMerged = isForceMergeComplete(backingIndex);
            if (alreadyForceMerged) {
                logger.trace("Already force merged {}", indexName);
                continue;
            }

            ByteSizeValue configuredFloorSegmentMerge = MergePolicyConfig.INDEX_MERGE_POLICY_FLOOR_SEGMENT_SETTING.get(
                backingIndex.getSettings()
            );
            Integer configuredMergeFactor = MergePolicyConfig.INDEX_MERGE_POLICY_MERGE_FACTOR_SETTING.get(backingIndex.getSettings());
            if ((configuredFloorSegmentMerge == null || configuredFloorSegmentMerge.equals(targetMergePolicyFloorSegment) == false)
                || (configuredMergeFactor == null || configuredMergeFactor.equals(targetMergePolicyFactor) == false)) {
                UpdateSettingsRequest updateMergePolicySettingsRequest = new UpdateSettingsRequest();
                updateMergePolicySettingsRequest.indices(indexName);
                updateMergePolicySettingsRequest.settings(
                    Settings.builder()
                        .put(MergePolicyConfig.INDEX_MERGE_POLICY_FLOOR_SEGMENT_SETTING.getKey(), targetMergePolicyFloorSegment)
                        .put(MergePolicyConfig.INDEX_MERGE_POLICY_MERGE_FACTOR_SETTING.getKey(), targetMergePolicyFactor)
                );
                updateMergePolicySettingsRequest.masterNodeTimeout(TimeValue.MAX_VALUE);
                affectedIndices.add(index);
                transportActionsDeduplicator.executeOnce(
                    updateMergePolicySettingsRequest,
                    new ErrorRecordingActionListener(
                        indexName,
                        errorStore,
                        Strings.format(
                            "Data stream lifecycle encountered an error trying to to update settings [%s] for index [%s]",
                            updateMergePolicySettingsRequest.settings().keySet(),
                            indexName
                        )
                    ),
                    (req, reqListener) -> updateIndexSetting(updateMergePolicySettingsRequest, reqListener)
                );
            } else {
                affectedIndices.add(index);
                ForceMergeRequest forceMergeRequest = new ForceMergeRequest(indexName);
                // time to force merge the index
                transportActionsDeduplicator.executeOnce(
                    new ForceMergeRequestWrapper(forceMergeRequest),
                    new ErrorRecordingActionListener(
                        indexName,
                        errorStore,
                        Strings.format(
                            "Data stream lifecycle encountered an error trying to force merge index [%s]. Data stream lifecycle will "
                                + "attempt to force merge the index on its next run.",
                            indexName
                        )
                    ),
                    (req, reqListener) -> forceMergeIndex(forceMergeRequest, reqListener)
                );
            }
        }
        return affectedIndices;
    }

    private void rolloverDataStream(String writeIndexName, RolloverRequest rolloverRequest, ActionListener<Void> listener) {
        // "saving" the rollover target name here so we don't capture the entire request
        String rolloverTarget = rolloverRequest.getRolloverTarget();
        logger.trace("Data stream lifecycle issues rollover request for data stream [{}]", rolloverTarget);
        client.admin().indices().rolloverIndex(rolloverRequest, new ActionListener<>() {
            @Override
            public void onResponse(RolloverResponse rolloverResponse) {
                // Log only when the conditions were met and the index was rolled over.
                if (rolloverResponse.isRolledOver()) {
                    List<String> metConditions = rolloverResponse.getConditionStatus()
                        .entrySet()
                        .stream()
                        .filter(Map.Entry::getValue)
                        .map(Map.Entry::getKey)
                        .toList();
                    logger.info(
                        "Data stream lifecycle successfully rolled over datastream [{}] due to the following met rollover "
                            + "conditions {}. The new index is [{}]",
                        rolloverTarget,
                        metConditions,
                        rolloverResponse.getNewIndex()
                    );
                }
                listener.onResponse(null);
            }

            @Override
            public void onFailure(Exception e) {
                DataStream dataStream = clusterService.state().metadata().dataStreams().get(rolloverTarget);
                if (dataStream == null || dataStream.getWriteIndex().getName().equals(writeIndexName) == false) {
                    // the data stream has another write index so no point in recording an error for the previous write index we were
                    // attempting to rollover
                    // if there are persistent issues with rolling over this data stream, the next data stream lifecycle run will attempt to
                    // rollover the _current_ write index and the error problem should surface then
                    listener.onResponse(null);
                } else {
                    // the data stream has NOT been rolled over since we issued our rollover request, so let's record the
                    // error against the data stream's write index.
                    listener.onFailure(e);
                }
            }
        });
    }

    private void updateIndexSetting(UpdateSettingsRequest updateSettingsRequest, ActionListener<Void> listener) {
        assert updateSettingsRequest.indices() != null && updateSettingsRequest.indices().length == 1
            : "Data stream lifecycle service updates the settings for one index at a time";
        // "saving" the index name here so we don't capture the entire request
        String targetIndex = updateSettingsRequest.indices()[0];
        logger.trace(
            "Data stream lifecycle service issues request to update settings [{}] for index [{}]",
            updateSettingsRequest.settings().keySet(),
            targetIndex
        );
        client.admin().indices().updateSettings(updateSettingsRequest, new ActionListener<>() {
            @Override
            public void onResponse(AcknowledgedResponse acknowledgedResponse) {
                logger.info(
                    "Data stream lifecycle service successfully updated settings [{}] for index index [{}]",
                    updateSettingsRequest.settings().keySet(),
                    targetIndex
                );
                listener.onResponse(null);
            }

            @Override
            public void onFailure(Exception e) {
                if (e instanceof IndexNotFoundException) {
                    // index was already deleted, treat this as a success
                    errorStore.clearRecordedError(targetIndex);
                    listener.onResponse(null);
                    return;
                }

                listener.onFailure(e);
            }
        });
    }

    private void addIndexBlock(AddIndexBlockRequest addIndexBlockRequest, ActionListener<Void> listener) {
        assert addIndexBlockRequest.indices() != null && addIndexBlockRequest.indices().length == 1
            : "Data stream lifecycle service updates the index block for one index at a time";
        // "saving" the index name here so we don't capture the entire request
        String targetIndex = addIndexBlockRequest.indices()[0];
        logger.trace(
            "Data stream lifecycle service issues request to add block [{}] for index [{}]",
            addIndexBlockRequest.getBlock(),
            targetIndex
        );
        client.admin().indices().addBlock(addIndexBlockRequest, new ActionListener<>() {
            @Override
            public void onResponse(AddIndexBlockResponse addIndexBlockResponse) {
                if (addIndexBlockResponse.isAcknowledged()) {
                    logger.info(
                        "Data stream lifecycle service successfully added block [{}] for index index [{}]",
                        addIndexBlockRequest.getBlock(),
                        targetIndex
                    );
                    listener.onResponse(null);
                } else {
                    Optional<AddIndexBlockResponse.AddBlockResult> resultForTargetIndex = addIndexBlockResponse.getIndices()
                        .stream()
                        .filter(blockResult -> blockResult.getIndex().getName().equals(targetIndex))
                        .findAny();
                    if (resultForTargetIndex.isEmpty()) {
                        // blimey
                        // this is weird, we don't have a result for our index, so let's treat this as a success and the next DSL run will
                        // check if we need to retry adding the block for this index
                        logger.trace(
                            "Data stream lifecycle service received an unacknowledged response when attempting to add the "
                                + "read-only block to index [{}], but the response didn't contain an explicit result for the index.",
                            targetIndex
                        );
                        listener.onFailure(
                            new ElasticsearchException("request to mark index [" + targetIndex + "] as read-only was not acknowledged")
                        );
                    } else if (resultForTargetIndex.get().hasFailures()) {
                        AddIndexBlockResponse.AddBlockResult blockResult = resultForTargetIndex.get();
                        if (blockResult.getException() != null) {
                            listener.onFailure(blockResult.getException());
                        } else {
                            List<AddIndexBlockResponse.AddBlockShardResult.Failure> shardFailures = new ArrayList<>(
                                blockResult.getShards().length
                            );
                            for (AddIndexBlockResponse.AddBlockShardResult shard : blockResult.getShards()) {
                                if (shard.hasFailures()) {
                                    shardFailures.addAll(Arrays.asList(shard.getFailures()));
                                }
                            }
                            assert shardFailures.isEmpty() == false
                                : "The block response must have shard failures as the global "
                                    + "exception is null. The block result is: "
                                    + blockResult;
                            String errorMessage = org.elasticsearch.common.Strings.collectionToDelimitedString(
                                shardFailures.stream().map(org.elasticsearch.common.Strings::toString).collect(Collectors.toList()),
                                ","
                            );
                            listener.onFailure(new ElasticsearchException(errorMessage));
                        }
                    } else {
                        listener.onFailure(
                            new ElasticsearchException("request to mark index [" + targetIndex + "] as read-only was not acknowledged")
                        );
                    }
                }
            }

            @Override
            public void onFailure(Exception e) {
                if (e instanceof IndexNotFoundException) {
                    // index was already deleted, treat this as a success
                    errorStore.clearRecordedError(targetIndex);
                    listener.onResponse(null);
                    return;
                }

                listener.onFailure(e);
            }
        });
    }

    private void deleteIndex(DeleteIndexRequest deleteIndexRequest, String reason, ActionListener<Void> listener) {
        assert deleteIndexRequest.indices() != null && deleteIndexRequest.indices().length == 1
            : "Data stream lifecycle deletes one index at a time";
        // "saving" the index name here so we don't capture the entire request
        String targetIndex = deleteIndexRequest.indices()[0];
        logger.trace("Data stream lifecycle issues request to delete index [{}]", targetIndex);
        client.admin().indices().delete(deleteIndexRequest, new ActionListener<>() {
            @Override
            public void onResponse(AcknowledgedResponse acknowledgedResponse) {
                if (acknowledgedResponse.isAcknowledged()) {
                    logger.info("Data stream lifecycle successfully deleted index [{}] due to {}", targetIndex, reason);
                } else {
                    logger.trace(
                        "The delete request for index [{}] was not acknowledged. Data stream lifecycle service will retry on the"
                            + " next run if the index still exists",
                        targetIndex
                    );
                }
                listener.onResponse(null);
            }

            @Override
            public void onFailure(Exception e) {
                if (e instanceof IndexNotFoundException) {
                    // index was already deleted, treat this as a success
                    errorStore.clearRecordedError(targetIndex);
                    listener.onResponse(null);
                    return;
                }

                if (e instanceof SnapshotInProgressException) {
                    logger.info(
                        "Data stream lifecycle was unable to delete index [{}] because it's currently being snapshot. Retrying on "
                            + "the next data stream lifecycle run",
                        targetIndex
                    );
                }
                listener.onFailure(e);
            }
        });
    }

    private void downsampleIndex(DownsampleAction.Request request, ActionListener<Void> listener) {
        String sourceIndex = request.getSourceIndex();
        String downsampleIndex = request.getTargetIndex();
        logger.info("Data stream lifecycle issuing request to downsample index [{}] to index [{}]", sourceIndex, downsampleIndex);
        client.execute(DownsampleAction.INSTANCE, request, new ActionListener<>() {
            @Override
            public void onResponse(AcknowledgedResponse acknowledgedResponse) {
                assert acknowledgedResponse.isAcknowledged() : "the downsample response is always acknowledged";
                logger.info("Data stream lifecycle successfully downsampled index [{}] to index [{}]", sourceIndex, downsampleIndex);
                listener.onResponse(null);
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(e);
            }
        });
    }

    /*
     * This method executes the given force merge request. Once the request has completed successfully it writes a timestamp as custom
     * metadata in the cluster state indicating when the force merge has completed. The listener is notified after the cluster state
     * update has been made, or when the forcemerge fails or the write of the to the cluster state fails.
     */
    private void forceMergeIndex(ForceMergeRequest forceMergeRequest, ActionListener<Void> listener) {
        assert forceMergeRequest.indices() != null && forceMergeRequest.indices().length == 1
            : "Data stream lifecycle force merges one index at a time";
        final String targetIndex = forceMergeRequest.indices()[0];
        logger.info("Data stream lifecycle is issuing a request to force merge index [{}]", targetIndex);
        client.admin().indices().forceMerge(forceMergeRequest, new ActionListener<>() {
            @Override
            public void onResponse(ForceMergeResponse forceMergeResponse) {
                if (forceMergeResponse.getFailedShards() > 0) {
                    DefaultShardOperationFailedException[] failures = forceMergeResponse.getShardFailures();
                    String message = Strings.format(
                        "Data stream lifecycle failed to forcemerge %d shards for index [%s] due to failures [%s]",
                        forceMergeResponse.getFailedShards(),
                        targetIndex,
                        failures == null
                            ? "unknown"
                            : Arrays.stream(failures).map(DefaultShardOperationFailedException::toString).collect(Collectors.joining(","))
                    );
                    onFailure(new ElasticsearchException(message));
                } else if (forceMergeResponse.getTotalShards() != forceMergeResponse.getSuccessfulShards()) {
                    String message = Strings.format(
                        "Force merge request only had %d successful shards out of a total of %d",
                        forceMergeResponse.getSuccessfulShards(),
                        forceMergeResponse.getTotalShards()
                    );
                    onFailure(new ElasticsearchException(message));
                } else {
                    logger.info("Data stream lifecycle successfully force merged index [{}]", targetIndex);
                    setForceMergeCompletedTimestamp(targetIndex, listener);
                }
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(e);
            }
        });
    }

    /*
     * This method sets the value of the custom index metadata field "force_merge_completed_timestamp" within the field
     * "data_stream_lifecycle" to value. The method returns immediately, but the update happens asynchronously and listener is notified on
     * success or failure.
     */
    private void setForceMergeCompletedTimestamp(String targetIndex, ActionListener<Void> listener) {
        forceMergeClusterStateUpdateTaskQueue.submitTask(
            Strings.format("Adding force merge complete marker to cluster state for [%s]", targetIndex),
            new UpdateForceMergeCompleteTask(listener, targetIndex, threadPool),
            null
        );
    }

    /*
     * Returns true if a value has been set for the custom index metadata field "force_merge_completed_timestamp" within the field
     * "data_stream_lifecycle".
     */
    private boolean isForceMergeComplete(IndexMetadata backingIndex) {
        Map<String, String> customMetadata = backingIndex.getCustomData(LIFECYCLE_CUSTOM_INDEX_METADATA_KEY);
        return customMetadata != null && customMetadata.containsKey(FORCE_MERGE_COMPLETED_TIMESTAMP_METADATA_KEY);
    }

    @Nullable
    static TimeValue getRetentionConfiguration(DataStream dataStream) {
        if (dataStream.getLifecycle() == null) {
            return null;
        }
        return dataStream.getLifecycle().getEffectiveDataRetention();
    }

    /**
     * Action listener that records the encountered failure using the provided recordError callback for the
     * provided target index. If the listener is notified of success it will clear the recorded entry for the provided
     * target index using the clearErrorRecord callback.
     */
    static class ErrorRecordingActionListener implements ActionListener<Void> {
        private final String targetIndex;
        private final DataStreamLifecycleErrorStore errorStore;
        private final String errorLogMessage;

        ErrorRecordingActionListener(String targetIndex, DataStreamLifecycleErrorStore errorStore, String errorLogMessage) {
            this.targetIndex = targetIndex;
            this.errorStore = errorStore;
            this.errorLogMessage = errorLogMessage;
        }

        @Override
        public void onResponse(Void unused) {
            errorStore.clearRecordedError(targetIndex);
        }

        @Override
        public void onFailure(Exception e) {
            recordAndLogError(targetIndex, errorStore, e, errorLogMessage);
        }
    }

    /**
     * Records the provided error for the index in the error store and logs the error message at `ERROR` level if the error for the index
     * is different to what's already in the error store.
     * This allows us to not spam the logs and only log new errors when we're about to record them in the store.
     */
    static void recordAndLogError(String targetIndex, DataStreamLifecycleErrorStore errorStore, Exception e, String logMessage) {
        String previousError = errorStore.recordError(targetIndex, e);
        if (previousError == null || previousError.equals(errorStore.getError(targetIndex)) == false) {
            logger.error(logMessage, e);
        } else {
            logger.trace(logMessage, e);
        }
    }

    static RolloverRequest getDefaultRolloverRequest(
        RolloverConfiguration rolloverConfiguration,
        String dataStream,
        TimeValue dataRetention
    ) {
        RolloverRequest rolloverRequest = new RolloverRequest(dataStream, null).masterNodeTimeout(TimeValue.MAX_VALUE);
        rolloverRequest.setConditions(rolloverConfiguration.resolveRolloverConditions(dataRetention));
        return rolloverRequest;
    }

    private void updatePollInterval(TimeValue newInterval) {
        this.pollInterval = newInterval;
        maybeScheduleJob();
    }

    private void updateRolloverConfiguration(RolloverConfiguration newRolloverConfiguration) {
        this.rolloverConfiguration = newRolloverConfiguration;
    }

    private void updateMergePolicyFloorSegment(ByteSizeValue newFloorSegment) {
        this.targetMergePolicyFloorSegment = newFloorSegment;
    }

    private void updateMergePolicyFactor(int newFactor) {
        this.targetMergePolicyFactor = newFactor;
    }

    private void cancelJob() {
        if (scheduler.get() != null) {
            scheduler.get().remove(LIFECYCLE_JOB_NAME);
            scheduledJob = null;
        }
    }

    private boolean isClusterServiceStoppedOrClosed() {
        final Lifecycle.State state = clusterService.lifecycleState();
        return state == Lifecycle.State.STOPPED || state == Lifecycle.State.CLOSED;
    }

    private void maybeScheduleJob() {
        if (this.isMaster == false) {
            return;
        }

        // don't schedule the job if the node is shutting down
        if (isClusterServiceStoppedOrClosed()) {
            logger.trace(
                "Skipping scheduling a data stream lifecycle job due to the cluster lifecycle state being: [{}] ",
                clusterService.lifecycleState()
            );
            return;
        }

        if (scheduler.get() == null) {
            scheduler.set(new SchedulerEngine(settings, clock));
            scheduler.get().register(this);
        }

        assert scheduler.get() != null : "scheduler should be available";
        scheduledJob = new SchedulerEngine.Job(LIFECYCLE_JOB_NAME, new TimeValueSchedule(pollInterval));
        scheduler.get().add(scheduledJob);
    }

    // public visibility for testing
    public DataStreamLifecycleErrorStore getErrorStore() {
        return errorStore;
    }

    /**
     * This is a ClusterStateTaskListener that writes the force_merge_completed_timestamp into the cluster state. It is meant to run in
     * STATE_UPDATE_TASK_EXECUTOR.
     */
    static class UpdateForceMergeCompleteTask implements ClusterStateTaskListener {
        private final ActionListener<Void> listener;
        private final String targetIndex;
        private final ThreadPool threadPool;

        UpdateForceMergeCompleteTask(ActionListener<Void> listener, String targetIndex, ThreadPool threadPool) {
            this.listener = listener;
            this.targetIndex = targetIndex;
            this.threadPool = threadPool;
        }

        ClusterState execute(ClusterState currentState) throws Exception {
            logger.debug("Updating cluster state with force merge complete marker for {}", targetIndex);
            IndexMetadata indexMetadata = currentState.metadata().index(targetIndex);
            Map<String, String> customMetadata = indexMetadata.getCustomData(LIFECYCLE_CUSTOM_INDEX_METADATA_KEY);
            Map<String, String> newCustomMetadata = new HashMap<>();
            if (customMetadata != null) {
                newCustomMetadata.putAll(customMetadata);
            }
            newCustomMetadata.put(FORCE_MERGE_COMPLETED_TIMESTAMP_METADATA_KEY, Long.toString(threadPool.absoluteTimeInMillis()));
            IndexMetadata updatededIndexMetadata = new IndexMetadata.Builder(indexMetadata).putCustom(
                LIFECYCLE_CUSTOM_INDEX_METADATA_KEY,
                newCustomMetadata
            ).build();
            Metadata metadata = Metadata.builder(currentState.metadata()).put(updatededIndexMetadata, true).build();
            return ClusterState.builder(currentState).metadata(metadata).build();
        }

        @Override
        public void onFailure(Exception e) {
            listener.onFailure(e);
        }
    }

    /**
     * This wrapper exists only to provide equals and hashCode implementations of a ForceMergeRequest for transportActionsDeduplicator.
     * It intentionally ignores forceMergeUUID (which ForceMergeRequest's equals/hashCode would have to if they existed) because we don't
     * care about it for data stream lifecycle deduplication. This class is non-private for the sake of unit testing, but should not be used
     * outside of Data Stream Lifecycle Service.
     */
    static final class ForceMergeRequestWrapper extends ForceMergeRequest {
        ForceMergeRequestWrapper(ForceMergeRequest original) {
            super(original.indices());
            this.maxNumSegments(original.maxNumSegments());
            this.onlyExpungeDeletes(original.onlyExpungeDeletes());
            this.flush(original.flush());
            this.indicesOptions(original.indicesOptions());
            this.setShouldStoreResult(original.getShouldStoreResult());
            this.setRequestId(original.getRequestId());
            this.timeout(original.timeout());
            this.setParentTask(original.getParentTask());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ForceMergeRequest that = (ForceMergeRequest) o;
            return Arrays.equals(indices, that.indices())
                && maxNumSegments() == that.maxNumSegments()
                && onlyExpungeDeletes() == that.onlyExpungeDeletes()
                && flush() == that.flush()
                && Objects.equals(indicesOptions(), that.indicesOptions())
                && getShouldStoreResult() == that.getShouldStoreResult()
                && getRequestId() == that.getRequestId()
                && Objects.equals(timeout(), that.timeout())
                && Objects.equals(getParentTask(), that.getParentTask());
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                Arrays.hashCode(indices),
                maxNumSegments(),
                onlyExpungeDeletes(),
                flush(),
                indicesOptions(),
                getShouldStoreResult(),
                getRequestId(),
                timeout(),
                getParentTask()
            );
        }
    }
}
