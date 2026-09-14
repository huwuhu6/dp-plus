package com.hmdp.ai.v2.semantic;
import java.math.BigDecimal;
public sealed interface ObservationPredicate permits ObservationPredicate.BooleanEquals, ObservationPredicate.NumericCompare, ObservationPredicate.ResultStateIs, ObservationPredicate.CategoryEquals {
    record BooleanEquals(boolean expected) implements ObservationPredicate { }
    record NumericCompare(NumericOperator operator, BigDecimal expected) implements ObservationPredicate { }
    record ResultStateIs(ResultState expected) implements ObservationPredicate { }
    record CategoryEquals(ObservationCategory expected) implements ObservationPredicate { }
    enum NumericOperator { GT, GTE, LT, LTE, EQ }
    enum ResultState { EMPTY, NON_EMPTY }
    enum ObservationCategory { SERVICE, QUEUE }
}
