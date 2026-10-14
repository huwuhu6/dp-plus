package com.hmdp.ai.v2.semantic;
import java.math.BigDecimal;
import java.util.regex.*;

/** 距离上限必须有原话的数值、单位和范围意图；“附近”不等于用户指定了3km。 */
public final class DistanceSource {
    private DistanceSource(){}
    private static final String NUMBER="([0-9]+(?:\\.[0-9]+)?|半|[零〇一二两三四五六七八九十百千]+)";
    private static final String UNIT="\\s*(公里|千米|km|米|m)";
    public static BigDecimal explicitMaxKm(String user){
        Matcher m=Pattern.compile(NUMBER+UNIT+"\\s*(?:以内|内|以下|范围|找餐厅)|(?:半径|不超过|最多)\\s*"+NUMBER+UNIT,Pattern.CASE_INSENSITIVE).matcher(user);
        if(!m.find())return null;String number=m.group(1)!=null?m.group(1):m.group(3);String unit=m.group(2)!=null?m.group(2):m.group(4);
        BigDecimal value=number.matches("[0-9].*")?new BigDecimal(number):number.equals("半")?new BigDecimal("0.5"):BigDecimal.valueOf(chinese(number));
        return unit.equalsIgnoreCase("m")||unit.equals("米")?value.movePointLeft(3):value;
    }
    private static int chinese(String n){int total=0,current=0;for(char c:n.toCharArray()){
        int unit=c=='十'?10:c=='百'?100:c=='千'?1000:0;
        if(unit>0){total+=(current==0?1:current)*unit;current=0;}else current=digit(c);
    }return total+current;}
    private static int digit(char c){return c=='两'?2:"一二三四五六七八九".indexOf(c)+1;}
}
