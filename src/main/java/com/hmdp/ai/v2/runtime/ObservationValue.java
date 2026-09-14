package com.hmdp.ai.v2.runtime;

import java.math.BigDecimal;

/** Typed, closed observation payload used by plan guards; display text is never a predicate input. */
public sealed interface ObservationValue permits ObservationValue.BooleanValue, ObservationValue.NumericValue,
        ObservationValue.CategoryValue, ObservationValue.TextValue {
    record BooleanValue(boolean value) implements ObservationValue { }
    record NumericValue(BigDecimal value) implements ObservationValue { }
    record CategoryValue(String value) implements ObservationValue { }
    record TextValue(String value) implements ObservationValue { }
}
