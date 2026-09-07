package com.hmdp.ai.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.entity.AiReviewDocument;
import com.hmdp.ai.mapper.AiReviewDocumentMapper;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.mapper.BlogMapper;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SearchShopEvidenceTool extends BaseAgentTool {
    @Resource private AiReviewDocumentMapper reviewMapper;
    @Resource private BlogMapper blogMapper;
    @Resource private BlogCommentsMapper commentMapper;

    @Override public String name() { return "search_shop_evidence"; }
    @Override public String description() { return "检索某家商户的评价证据、探店笔记与笔记评论。用户询问主观体验、口味强弱（重口/清淡/辣咸油腻）、环境、服务、排队、适合场景或口碑时使用；没有主题命中时必须说明证据不足。"; }
    @Override public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("shopId", property("integer", "商户 ID；用户说这家时可以省略。"));
        properties.put("topic", property("string", "用户关心的主题，例如环境、排队、服务、约会；未知时传空字符串。"));
        return objectSchema(properties);
    }
    @Override public AgentToolResult execute(Map<String, Object> input) {
        Long shopId = shopId(input);
        if (shopId == null) throw new IllegalArgumentException("当前没有可查询评价的商户");
        String topic = input.get("topic") == null ? "" : String.valueOf(input.get("topic"));
        List<String> evidence = new ArrayList<String>();
        List<AiReviewDocument> documents = reviewMapper.selectList(new QueryWrapper<AiReviewDocument>().eq("shop_id", shopId).orderByDesc("sentiment").last("limit 4"));
        boolean topicMatched = topic.trim().isEmpty();
        for (AiReviewDocument document : documents) {
            boolean matched = topic.trim().isEmpty() || contains(document.getContent(), topic) || contains(document.getTags(), topic);
            if (matched) {
                topicMatched = true;
                evidence.add(compact(document.getContent()));
            }
        }
        if (!topicMatched) {
            for (AiReviewDocument document : documents) {
                if (evidence.size() >= 2) break;
                evidence.add(compact(document.getContent()));
            }
        }
        List<Blog> blogs = blogMapper.selectList(new QueryWrapper<Blog>().eq("shop_id", shopId).orderByDesc("liked").last("limit 2"));
        for (Blog blog : blogs) {
            evidence.add("[探店笔记] " + compact(blog.getTitle()) + "：" + compact(blog.getContent()));
            List<BlogComments> comments = commentMapper.selectList(new QueryWrapper<BlogComments>().eq("blog_id", blog.getId()).eq("status", 0).orderByDesc("liked").last("limit 2"));
            for (BlogComments comment : comments) evidence.add("[笔记评论] " + compact(comment.getContent()));
        }
        if (evidence.size() > 6) evidence = evidence.subList(0, 6);
        StringBuilder text = new StringBuilder(evidence.isEmpty() ? "当前没有可引用的评价或探店证据。"
                : (!topic.trim().isEmpty() && !topicMatched
                ? "现有评价没有明确提到“" + topic + "”，只能提供一般评价证据，暂时不能据此确定该主题："
                : "可引用的评价证据："));
        for (String item : evidence) text.append("\n- ").append(item);
        AgentToolResult result = new AgentToolResult().summary("检索到 " + evidence.size() + " 条评价与笔记证据").displayText(text.toString());
        result.getFacts().put("evidence", evidence);
        result.getFacts().put("topic", topic);
        result.getFacts().put("topicMatched", topicMatched);
        return result;
    }
    private boolean contains(String source, String expected) { return source != null && source.contains(expected); }
    private String compact(String source) {
        if (source == null) return "";
        String value = source.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return value.length() > 160 ? value.substring(0, 160) + "..." : value;
    }
}
