package com.hmdp.ai.v2.runtime;

import com.hmdp.ai.v2.semantic.DiningCriteria;
import java.util.*;
import java.util.regex.*;

/** 只检验明示食材与辣度的来源/覆盖，不从病名推导禁忌或给商户做安全诊断。 */
final class DietaryRestrictionSource {
    private static final Pattern NEGATIVE=Pattern.compile("(?:不能吃|不可吃|不可以吃|不吃|忌食|避免食用|不要加)([^，。；！？?!]+)");
    private static final Pattern SPICE=Pattern.compile("(?:不能吃太辣|不能太辣|不能吃辣|不要辣|不辣)");
    private static final Set<String> INGREDIENTS=Set.of("海鲜","内脏","花生","坚果","牛奶","鸡蛋","虾","蟹","贝类","鱼","牛肉","猪肉","羊肉","鸡肉","酒精");
    static boolean ingredientTerm(String value){return INGREDIENTS.contains(value);}
    static String withoutRestrictions(String user){return NEGATIVE.matcher(user).replaceAll("");}
    static boolean conditionalMeal(String user){return user.matches("(?s).*(?:不辣|不要辣).*(?:就去|才去|就选|才选).*" );}
    private static List<String> negativeFood(String user){var m=NEGATIVE.matcher(user);List<String> parts=new ArrayList<>();
        while(m.find()){String part=m.group(1).split("(?:也|但|还是|换|并且|或者)",2)[0];if(!part.contains("辣"))parts.add(part.replaceAll("[\\s、和及]",""));}return parts;}
    static String requiredSpice(String user){if(conditionalMeal(user))return null;var m=SPICE.matcher(user);return m.find()?m.group():null;}
    static boolean supported(String user,DiningCriteria.DietaryRestrictions restrictions){
        if(restrictions==null)return true;
        for(String value:restrictions.avoidedIngredients()) {
            if(!ingredientTerm(value) && com.hmdp.ai.util.CuisineCanonicalizer.knownAliases().contains(value))return false;
            if(negativeFood(user).stream().noneMatch(p->p.contains(value)))return false;
        }
        String spice=restrictions.spiceRequirement();return spice==null || user.contains(spice) && SPICE.matcher(spice).matches();
    }
    static boolean covered(String user,DiningCriteria.DietaryRestrictions restrictions,List<String> excludedCuisines){
        if(!supported(user,restrictions))return false;
        List<String> terms=new ArrayList<>(restrictions==null?List.of():restrictions.avoidedIngredients());
        terms.addAll(excludedCuisines.stream().filter(c->!ingredientTerm(c)).toList());
        for(String clause:negativeFood(user)){String remaining=clause;for(String term:terms)remaining=remaining.replace(term,"");if(!remaining.isBlank())return false;}
        String spice=requiredSpice(user);return spice==null || restrictions!=null && spice.equals(restrictions.spiceRequirement());
    }
}
