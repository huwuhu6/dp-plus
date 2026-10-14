package com.hmdp.ai.v2.evidence;

/** 只读事实提供者的类型化主餐证据；LLM 字符串、饮料与“少辣”均不能证明“不辣就去”。 */
public record MainMealSpiceEvidence(long shopId,String mealName,boolean mainMeal,Preparation preparation,String source) {
    public enum Preparation { NON_SPICY, REDUCED_SPICE, UNKNOWN }
    public boolean admissibleFor(long id){return shopId==id && mainMeal && mealName!=null && !mealName.isBlank()
            && source!=null && !source.isBlank() && preparation!=null && preparation!=Preparation.UNKNOWN;}
}
