/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.planner;

import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.aggregation.Aggregator;
import org.elasticsearch.compute.aggregation.AggregatorFunctionSupplier;
import org.elasticsearch.compute.aggregation.AggregatorMode;
import org.elasticsearch.compute.aggregation.GroupingAggregator;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.operator.AggregationOperator;
import org.elasticsearch.compute.operator.HashAggregationOperator;
import org.elasticsearch.compute.operator.HashAggregationOperator.HashAggregationOperatorFactory;
import org.elasticsearch.compute.operator.Operator;
import org.elasticsearch.xpack.esql.EsqlIllegalArgumentException;
import org.elasticsearch.xpack.esql.plan.physical.AggregateExec;
import org.elasticsearch.xpack.esql.planner.LocalExecutionPlanner.LocalExecutionPlannerContext;
import org.elasticsearch.xpack.esql.planner.LocalExecutionPlanner.PhysicalOperation;
import org.elasticsearch.xpack.ql.expression.Alias;
import org.elasticsearch.xpack.ql.expression.Attribute;
import org.elasticsearch.xpack.ql.expression.Expression;
import org.elasticsearch.xpack.ql.expression.Expressions;
import org.elasticsearch.xpack.ql.expression.NameId;
import org.elasticsearch.xpack.ql.expression.NamedExpression;
import org.elasticsearch.xpack.ql.expression.function.aggregate.AggregateFunction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

abstract class AbstractPhysicalOperationProviders implements PhysicalOperationProviders {

    private final AggregateMapper aggregateMapper = new AggregateMapper();

    @Override
    public final PhysicalOperation groupingPhysicalOperation(
        AggregateExec aggregateExec,
        PhysicalOperation source,
        LocalExecutionPlannerContext context
    ) {
        Layout.Builder layout = new Layout.Builder();
        Operator.OperatorFactory operatorFactory = null;
        AggregateExec.Mode mode = aggregateExec.getMode();
        var aggregates = aggregateExec.aggregates();

        var sourceLayout = source.layout;

        if (aggregateExec.groupings().isEmpty()) {
            // not grouping
            List<Aggregator.Factory> aggregatorFactories = new ArrayList<>();

            // append channels to the layout
            if (mode == AggregateExec.Mode.FINAL) {
                layout.appendChannels(aggregates);
            } else {
                layout.appendChannels(aggregateMapper.mapNonGrouping(aggregates));
            }
            // create the agg factories
            aggregatesToFactory(
                aggregates,
                mode,
                sourceLayout,
                context.bigArrays(),
                false, // non-grouping
                s -> aggregatorFactories.add(s.supplier.aggregatorFactory(s.mode))
            );

            if (aggregatorFactories.isEmpty() == false) {
                operatorFactory = new AggregationOperator.AggregationOperatorFactory(
                    aggregatorFactories,
                    mode == AggregateExec.Mode.FINAL ? AggregatorMode.FINAL : AggregatorMode.INITIAL
                );
            }
        } else {
            // grouping
            List<GroupingAggregator.Factory> aggregatorFactories = new ArrayList<>();
            List<GroupSpec> groupSpecs = new ArrayList<>(aggregateExec.groupings().size());
            for (Expression group : aggregateExec.groupings()) {
                var groupAttribute = Expressions.attribute(group);
                if (groupAttribute == null) {
                    throw new EsqlIllegalArgumentException("Unexpected non-named expression[{}] as grouping in [{}]", group, aggregateExec);
                }
                Set<NameId> grpAttribIds = new HashSet<>();
                grpAttribIds.add(groupAttribute.id());

                /*
                 * Check for aliasing in aggregates which occurs in two cases (due to combining project + stats):
                 *  - before stats (keep x = a | stats by x) which requires the partial input to use a's channel
                 *  - after  stats (stats by a | keep x = a) which causes the output layout to refer to the follow-up alias
                 */
                for (NamedExpression agg : aggregates) {
                    if (agg instanceof Alias a) {
                        if (a.child() instanceof Attribute attr) {
                            if (groupAttribute.id().equals(attr.id())) {
                                grpAttribIds.add(a.id());
                                // TODO: investigate whether a break could be used since it shouldn't be possible to have multiple
                                // attributes
                                // pointing to the same attribute
                            }
                            // partial mode only
                            // check if there's any alias used in grouping - no need for the final reduction since the intermediate data
                            // is in the output form
                            // if the group points to an alias declared in the aggregate, use the alias child as source
                            else if (mode == AggregateExec.Mode.PARTIAL) {
                                if (groupAttribute.semanticEquals(a.toAttribute())) {
                                    groupAttribute = attr;
                                    break;
                                }
                            }
                        }
                    }
                }
                layout.appendChannel(grpAttribIds);
                groupSpecs.add(new GroupSpec(source.layout.getChannel(groupAttribute.id()), groupAttribute));
            }

            if (mode == AggregateExec.Mode.FINAL) {
                for (var agg : aggregates) {
                    if (agg instanceof Alias alias && alias.child() instanceof AggregateFunction) {
                        layout.appendChannel(alias.id());
                    }
                }
            } else {
                layout.appendChannels(aggregateMapper.mapGrouping(aggregates));
            }

            // create the agg factories
            aggregatesToFactory(
                aggregates,
                mode,
                sourceLayout,
                context.bigArrays(),
                true, // grouping
                s -> aggregatorFactories.add(s.supplier.groupingAggregatorFactory(s.mode))
            );

            if (groupSpecs.size() == 1 && groupSpecs.get(0).channel == null) {
                operatorFactory = ordinalGroupingOperatorFactory(
                    source,
                    aggregateExec,
                    aggregatorFactories,
                    groupSpecs.get(0).attribute,
                    groupSpecs.get(0).elementType(),
                    context
                );
            } else {
                operatorFactory = new HashAggregationOperatorFactory(
                    groupSpecs.stream().map(GroupSpec::toHashGroupSpec).toList(),
                    aggregatorFactories,
                    context.pageSize(aggregateExec.estimatedRowSize()),
                    context.bigArrays()
                );
            }
        }
        if (operatorFactory != null) {
            return source.with(operatorFactory, layout.build());
        }
        throw new EsqlIllegalArgumentException("no operator factory");
    }

    /***
     * Creates a standard layout for intermediate aggregations, typically used across exchanges.
     * Puts the group first, followed by each aggregation.
     *
     * It's similar to the code above (groupingPhysicalOperation) but ignores the factory creation.
     */
    public static List<Attribute> intermediateAttributes(List<? extends NamedExpression> aggregates, List<? extends Expression> groupings) {
        var aggregateMapper = new AggregateMapper();

        List<Attribute> attrs = new ArrayList<>();

        // no groups
        if (groupings.isEmpty()) {
            attrs = Expressions.asAttributes(aggregateMapper.mapNonGrouping(aggregates));
        }
        // groups
        else {
            for (Expression group : groupings) {
                var groupAttribute = Expressions.attribute(group);
                if (groupAttribute == null) {
                    throw new EsqlIllegalArgumentException("Unexpected non-named expression[{}] as grouping", group);
                }
                Set<NameId> grpAttribIds = new HashSet<>();
                grpAttribIds.add(groupAttribute.id());

                /*
                 * Check for aliasing in aggregates which occurs in two cases (due to combining project + stats):
                 *  - before stats (keep x = a | stats by x) which requires the partial input to use a's channel
                 *  - after  stats (stats by a | keep x = a) which causes the output layout to refer to the follow-up alias
                 */
                for (NamedExpression agg : aggregates) {
                    if (agg instanceof Alias a) {
                        if (a.child() instanceof Attribute attr) {
                            if (groupAttribute.id().equals(attr.id())) {
                                grpAttribIds.add(a.id());
                                // TODO: investigate whether a break could be used since it shouldn't be possible to have multiple
                                // attributes
                                // pointing to the same attribute
                            }
                        }
                    }
                }
                attrs.add(groupAttribute);
            }

            attrs.addAll(Expressions.asAttributes(aggregateMapper.mapGrouping(aggregates)));
        }
        return attrs;
    }

    private record AggFunctionSupplierContext(AggregatorFunctionSupplier supplier, AggregatorMode mode) {}

    private void aggregatesToFactory(
        List<? extends NamedExpression> aggregates,
        AggregateExec.Mode mode,
        Layout layout,
        BigArrays bigArrays,
        boolean grouping,
        Consumer<AggFunctionSupplierContext> consumer
    ) {
        for (NamedExpression ne : aggregates) {
            if (ne instanceof Alias alias) {
                var child = alias.child();
                if (child instanceof AggregateFunction aggregateFunction) {
                    AggregatorMode aggMode = null;
                    List<? extends NamedExpression> sourceAttr;

                    if (mode == AggregateExec.Mode.PARTIAL) {
                        aggMode = AggregatorMode.INITIAL;
                        // TODO: this needs to be made more reliable - use casting to blow up when dealing with expressions (e+1)
                        sourceAttr = List.of(Expressions.attribute(aggregateFunction.field()));
                    } else if (mode == AggregateExec.Mode.FINAL) {
                        aggMode = AggregatorMode.FINAL;
                        if (grouping) {
                            sourceAttr = aggregateMapper.mapGrouping(aggregateFunction);
                        } else {
                            sourceAttr = aggregateMapper.mapNonGrouping(aggregateFunction);
                        }
                    } else {
                        throw new EsqlIllegalArgumentException("illegal aggregation mode");
                    }
                    var aggParams = aggregateFunction.parameters();
                    Object[] params = new Object[aggParams.size()];
                    for (int i = 0; i < params.length; i++) {
                        params[i] = aggParams.get(i).fold();
                    }

                    List<Integer> inputChannels = sourceAttr.stream().map(NamedExpression::id).map(layout::getChannel).toList();
                    assert inputChannels != null && inputChannels.size() > 0 && inputChannels.stream().allMatch(i -> i >= 0);
                    if (aggregateFunction instanceof ToAggregator agg) {
                        consumer.accept(new AggFunctionSupplierContext(agg.supplier(bigArrays, inputChannels), aggMode));
                    } else {
                        throw new EsqlIllegalArgumentException("aggregate functions must extend ToAggregator");
                    }
                }
            }
        }
    }

    private record GroupSpec(Integer channel, Attribute attribute) {
        HashAggregationOperator.GroupSpec toHashGroupSpec() {
            if (channel == null) {
                throw new EsqlIllegalArgumentException("planned to use ordinals but tried to use the hash instead");
            }
            return new HashAggregationOperator.GroupSpec(channel, elementType());
        }

        ElementType elementType() {
            return LocalExecutionPlanner.toElementType(attribute.dataType());
        }
    }

    /**
     * Build a grouping operator that operates on ordinals if possible.
     */
    public abstract Operator.OperatorFactory ordinalGroupingOperatorFactory(
        PhysicalOperation source,
        AggregateExec aggregateExec,
        List<GroupingAggregator.Factory> aggregatorFactories,
        Attribute attrSource,
        ElementType groupType,
        LocalExecutionPlannerContext context
    );
}
