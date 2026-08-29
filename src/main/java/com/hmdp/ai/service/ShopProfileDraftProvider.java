package com.hmdp.ai.service;

import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.ai.entity.ShopReview;

import java.util.List;

/** Produces review-derived fields without changing merchant-maintained facts. */
public interface ShopProfileDraftProvider {
    ShopProfileDraftGenerator.ProfileDraft generate(AiShopProfile profile, List<ShopReview> reviews);
}
