package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.dto.LocalDemoDatasetRequest;
import com.hmdp.ai.dto.LocalDemoDatasetResponse;
import com.hmdp.ai.entity.AiReviewDocument;
import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.ai.mapper.AiReviewDocumentMapper;
import com.hmdp.ai.mapper.AiShopProfileMapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/** Generates explicitly synthetic, idempotent LOCAL_DEMO data for local recommendation evaluation. */
@Service
public class LocalDemoDatasetService {
    private static final List<String> CUISINES = Arrays.asList("日料", "火锅", "烧烤", "粤菜", "西餐", "咖啡");
    @Resource private ShopMapper shopMapper;
    @Resource private AiShopProfileMapper profileMapper;
    @Resource private AiReviewDocumentMapper reviewMapper;

    public LocalDemoDatasetResponse generate(LocalDemoDatasetRequest request) {
        validate(request);
        LocalDemoDatasetResponse response = new LocalDemoDatasetResponse();
        Random random = new Random(request.getSeed());
        for (LocalDemoDatasetRequest.City city : request.getCities()) {
            for (int index = 1; index <= request.getShopsPerCity(); index++) {
                response.setRequestedShops(response.getRequestedShops() + 1);
                String cuisine = CUISINES.get((index - 1) % CUISINES.size());
                String district = city.getDistricts().get((index - 1) % city.getDistricts().size());
                String name = "本地模拟-" + city.getCity() + "-" + cuisine + "-" + String.format("%03d", index);
                Shop shop = shopMapper.selectOne(new QueryWrapper<Shop>().eq("name", name));
                if (shop == null) {
                    shop = new Shop(); shop.setName(name); shop.setTypeId(1L); shop.setImages(""); response.setInsertedShops(response.getInsertedShops() + 1);
                } else response.setUpdatedShops(response.getUpdatedShops() + 1);
                shop.setProvince(city.getProvince()); shop.setCity(city.getCity()); shop.setDistrict(district); shop.setArea(district);
                shop.setAddress(district + "本地模拟餐饮街" + index + "号");
                shop.setX(city.getLongitude() + (random.nextDouble() - 0.5D) * 0.08D);
                shop.setY(city.getLatitude() + (random.nextDouble() - 0.5D) * 0.08D);
                shop.setAvgPrice(45L + (long) ((index * 17) % 220)); shop.setSold(100 + index * 13);
                shop.setComments(40 + index * 7); shop.setScore(40 + index % 10); shop.setOpenHours("10:00-22:00");
                if (shop.getId() == null) shopMapper.insert(shop); else shopMapper.updateById(shop);
                upsertProfile(shop, cuisine, index); response.setProfileCount(response.getProfileCount() + 1);
                upsertReview(shop, cuisine, index, 1); upsertReview(shop, cuisine, index, 2); response.setReviewCount(response.getReviewCount() + 2);
            }
            response.getShopsByCity().put(city.getCity(), Math.toIntExact(shopMapper.selectCount(
                    new QueryWrapper<Shop>().eq("city", city.getCity()))));
        }
        return response;
    }

    /** Removes only synthetic shops for a city, together with their generated AI records. */
    public int purgeGeneratedCity(String city) {
        if (blank(city) || (city.contains("?") && !"???".equals(city))) throw new IllegalArgumentException("城市名称无效");
        List<Shop> shops = shopMapper.selectList(new QueryWrapper<Shop>().eq("city", city).likeRight("name", "本地模拟-"));
        List<Long> ids = shops.stream().map(Shop::getId).collect(Collectors.toList());
        if (ids.isEmpty()) return 0;
        reviewMapper.delete(new QueryWrapper<AiReviewDocument>().in("shop_id", ids).eq("source_type", "LOCAL_DEMO"));
        profileMapper.delete(new QueryWrapper<AiShopProfile>().in("shop_id", ids));
        shopMapper.deleteBatchIds(ids);
        return ids.size();
    }

    private void upsertProfile(Shop shop, String cuisine, int index) {
        AiShopProfile profile = profileMapper.selectOne(new QueryWrapper<AiShopProfile>().eq("shop_id", shop.getId()));
        if (profile == null) { profile = new AiShopProfile(); profile.setShopId(shop.getId()); }
        boolean quiet = index % 3 == 0; profile.setCuisine(cuisine); profile.setSceneTags(index % 2 == 0 ? "朋友聚餐,家庭聚餐" : "约会,工作日简餐");
        profile.setAmbienceTags(quiet ? "安静,舒适" : "热闹,轻松"); profile.setQueueLevel(index % 4 == 0 ? "HIGH" : "LOW");
        profile.setSummary("本地模拟" + cuisine + "餐厅，覆盖多轮推荐、预算和场景评测。");
        if (profile.getId() == null) profileMapper.insert(profile); else profileMapper.updateById(profile);
    }

    private void upsertReview(Shop shop, String cuisine, int index, int ordinal) {
        String key = "local-demo:v1:" + shop.getCity() + ":" + index + ":" + ordinal;
        AiReviewDocument review = reviewMapper.selectOne(new QueryWrapper<AiReviewDocument>().eq("source_key", key));
        if (review == null) { review = new AiReviewDocument(); review.setSourceKey(key); review.setShopId(shop.getId()); }
        review.setSourceType("LOCAL_DEMO"); review.setTags(cuisine + ",本地模拟," + (index % 3 == 0 ? "安静" : "聚餐")); review.setSentiment(ordinal == 1 ? 1 : 0);
        review.setContent("本地演示数据：" + cuisine + "菜品选择稳定，" + (index % 3 == 0 ? "环境安静，适合聊天。" : "适合多人聚餐和日常用餐。"));
        if (review.getId() == null) reviewMapper.insert(review); else reviewMapper.updateById(review);
    }

    private void validate(LocalDemoDatasetRequest request) {
        if (request == null || request.getCities() == null || request.getCities().isEmpty()) throw new IllegalArgumentException("至少提供一个城市");
        if (request.getShopsPerCity() == null || request.getShopsPerCity() < 1 || request.getShopsPerCity() > 1000) throw new IllegalArgumentException("每城市商户数量必须在 1 到 1000 之间");
        for (LocalDemoDatasetRequest.City city : request.getCities()) {
            if (city == null || invalidName(city.getProvince()) || invalidName(city.getCity()) || city.getLongitude() == null || city.getLatitude() == null || city.getDistricts() == null || city.getDistricts().isEmpty() || city.getDistricts().stream().anyMatch(this::invalidName)) throw new IllegalArgumentException("城市必须提供有效的省、市、中心坐标和至少一个区县");
        }
    }
    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private boolean invalidName(String value) { return blank(value) || value.contains("?"); }
}
