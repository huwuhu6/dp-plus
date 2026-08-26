package com.hmdp.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.ai.entity.AiConversationEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface AiConversationEventMapper extends BaseMapper<AiConversationEvent> {
    @Insert({"<script>",
            "INSERT INTO tbl_ai_conversation_event " +
                    "(id, chat_id, trace_id, turn_no, sequence_no, event_type, status, working_memory_id, parent_event_id, event_result, metadata, started_at, ended_at, created_at) VALUES ",
            "<foreach collection='events' item='event' separator=','>",
            "(#{event.id}, #{event.chatId}, #{event.traceId}, #{event.turnNo}, #{event.sequenceNo}, #{event.eventType}, #{event.status}, #{event.workingMemoryId}, #{event.parentEventId}, #{event.eventResult}, #{event.metadata}, #{event.startedAt}, #{event.endedAt}, #{event.createdAt})",
            "</foreach>",
            "</script>"})
    int insertBatch(@Param("events") List<AiConversationEvent> events);
}
