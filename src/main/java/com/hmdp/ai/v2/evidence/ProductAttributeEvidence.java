package com.hmdp.ai.v2.evidence;

/** 来自只读商品事实提供者；缺记录不代表没有商品，null available 表示未知。 */
public record ProductAttributeEvidence(long shopId,String attribute,Boolean available,String source) {
    public boolean supports(long id,String question){return shopId==id && attribute!=null && attribute.equals(question)
            && available!=null && source!=null && !source.isBlank();}
}
