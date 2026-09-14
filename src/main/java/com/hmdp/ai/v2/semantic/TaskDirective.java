package com.hmdp.ai.v2.semantic;

/** Task 是持续的消费决策目标，城市、菜系和预算只是其中可演进的 criteria。 */
public enum TaskDirective { CONTINUE, START_NEW, RESTORE, ABANDON }
