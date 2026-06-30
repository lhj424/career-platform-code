package com.xuelian.career.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuelian.career.entity.CareerProfile;
import com.xuelian.career.entity.Skill;
import com.xuelian.career.entity.UserSkillMastery;
import com.xuelian.career.mapper.CareerProfileMapper;
import com.xuelian.career.mapper.SkillMapper;
import com.xuelian.career.mapper.UserSkillMasteryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 多源技能融合引擎 — 将用户自报技能、测试验证技能、学习完成技能融合为统一画像
 * <p>
 * 融合规则：同名技能取最高置信度来源，优先级 TEST(0.9) > PROFILE(0.6) > LEARNING(0.4)
 * <p>
 * V2 修复：增加模糊名称匹配 + 未命中技能保留（虚拟ID），避免因名称不完全一致导致技能丢失
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillMergeService {

    private final CareerProfileMapper careerProfileMapper;
    private final UserSkillMasteryMapper masteryMapper;
    private final SkillMapper skillMapper;
    private final ObjectMapper objectMapper;

    /** 技能表全量缓存（带TTL，减少重复查库） */
    private final CopyOnWriteArrayList<Skill> skillCache = new CopyOnWriteArrayList<>();
    private volatile long cacheTimestamp = 0;
    private static final long CACHE_TTL_MS = 60_000; // 1分钟

    /** 等级 → 数值映射（中文） */
    private static final Map<String, Integer> CN_LEVEL_MAP = Map.of(
            "了解", 1, "掌握", 2, "熟练", 3, "精通", 4
    );
    /** 等级 → 数值映射（英文） */
    private static final Map<String, Integer> EN_LEVEL_MAP = Map.of(
            "BASIC", 1, "INTERMEDIATE", 2, "ADVANCED", 3, "EXPERT", 4
    );

    /**
     * 为用户构建多源融合技能画像
     *
     * @param userId 用户ID
     * @return skillId → MergedSkill 映射（可能含负数虚拟ID）
     */
    public Map<Long, MergedSkill> buildMergedSkillProfile(Long userId) {
        Map<Long, MergedSkill> profile = new LinkedHashMap<>();
        log.info("[SkillMerge] ========== 开始构建用户{}的融合画像 ==========", userId);

        // 层1：自报技能（confidence=0.6）
        mergeFromProfile(profile, userId);
        log.info("[SkillMerge] 层1(PROFILE)完成, 画像大小={}", profile.size());

        // 层2：测试验证技能（confidence=0.9，覆盖自报）
        mergeFromMastery(profile, userId, "TEST", 0.9, true);
        log.info("[SkillMerge] 层2(TEST)完成, 画像大小={}", profile.size());

        // 层3：学习完成技能（confidence=0.4，仅补充缺失）
        mergeFromMastery(profile, userId, "LEARNING", 0.4, false);
        log.info("[SkillMerge] 层3(LEARNING)完成, 最终画像大小={}", profile.size());
        
        return profile;
    }

    // ──────────────── 从 career_profile.skillTags 提取 ────────────────

    private void mergeFromProfile(Map<Long, MergedSkill> profile, Long userId) {
        CareerProfile cp = careerProfileMapper.selectOne(
                new LambdaQueryWrapper<CareerProfile>().eq(CareerProfile::getUserId, userId));
        if (cp == null) {
            log.warn("[SkillMerge] userId={} 的 career_profile 不存在", userId);
            return;
        }
        if (cp.getSkillTags() == null || cp.getSkillTags().isBlank()) {
            log.info("[SkillMerge] userId={} 的 skillTags 为空", userId);
            return;
        }
        log.info("[SkillMerge] userId={} 原始skillTags={}", userId, cp.getSkillTags());

        try {
            // 兼容两种格式：字符串数组 ["Java","Vue"] 或对象数组 [{"name":"Java"}]
            List<String> skillNames = new ArrayList<>();
            Object raw = objectMapper.readValue(cp.getSkillTags(), Object.class);
            if (raw instanceof List) {
                for (Object item : (List<?>) raw) {
                    if (item instanceof String && !((String) item).isBlank()) {
                        skillNames.add(((String) item).trim());
                    } else if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) item;
                        String n = (String) map.getOrDefault("name",
                                map.getOrDefault("skillName",
                                        map.getOrDefault("skill", "")));
                        if (n != null && !n.isBlank()) skillNames.add(n.trim());
                    }
                }
            }

            int matched = 0, virtualized = 0;
            for (String name : skillNames) {
                // 纯字符串数组没有等级信息，默认"掌握"(Lv2)
                int lv = 2;

                // V2: 多策略查找 skillId（精确标准化 → 包含匹配）
                Skill skill = findSkillByName(name);
                if (skill != null) {
                    profile.put(skill.getId(), new MergedSkill(skill.getName(), lv, 0.6, "PROFILE"));
                    matched++;
                } else {
                    // V2: 找不到词典条目也保留技能，用负数hashCode作为虚拟ID
                    long virtualId = -Math.abs((long) name.hashCode());
                    // 避免hash碰撞覆盖
                    while (profile.containsKey(virtualId)) { virtualId--; }
                    profile.put(virtualId, new MergedSkill(name.trim(), lv, 0.5, "PROFILE"));
                    virtualized++;
                }
            }
            log.info("[SkillMerge] userId={} 自报技能完成: 解析{}个, 命中词典={}, 虚拟保留={}, 当前画像总技能={}",
                    userId, skillNames.size(), matched, virtualized, profile.size());
        } catch (Exception e) {
            log.warn("[SkillMerge] 解析 career_profile.skillTags 失败: userId={}, error={}",
                    userId, e.getMessage());
        }
    }

    // ──────────────── 从 user_skill_mastery 提取 ────────────────

    private void mergeFromMastery(Map<Long, MergedSkill> profile, Long userId,
                                   String source, double confidence, boolean overwrite) {
        List<UserSkillMastery> list = masteryMapper.selectList(
                new LambdaQueryWrapper<UserSkillMastery>()
                        .eq(UserSkillMastery::getUserId, userId)
                        .eq(UserSkillMastery::getSource, source));
        for (UserSkillMastery m : list) {
            Long skillId = m.getSkillId();
            if (skillId == null) continue;

            if (!overwrite && profile.containsKey(skillId)) continue;

            String name = resolveSkillName(m);
            int lv = masteryLevelValue(m.getLevel());
            profile.put(skillId, new MergedSkill(name, lv, confidence, source));
        }
    }

    // ──────────────── V2: 技能名称模糊查找 ────────────────

    /**
     * 多策略技能名称查找（供评分引擎调用）
     * 策略优先级：精确标准化匹配 → 包含匹配（长度≥3）
     */
    public Skill findSkillByName(String name) {
        if (name == null || name.isBlank()) return null;
        String normalized = normalizeSkillName(name);

        List<Skill> allSkills = getAllSkills();

        // 策略1: 标准化后精确匹配
        for (Skill s : allSkills) {
            if (normalized.equals(normalizeSkillName(s.getName()))) return s;
        }
        // 策略2: 双向包含匹配（避免短词如"Go"、"C"误匹配）
        if (normalized.length() >= 3) {
            for (Skill s : allSkills) {
                String sn = normalizeSkillName(s.getName());
                if (sn.length() >= 3 && (sn.contains(normalized) || normalized.contains(sn))) return s;
            }
        }
        return null;
    }

    /**
     * 按名称在融合画像中进行模糊查找（供评分引擎做兜底匹配）
     * 支持虚拟ID（负数）和真实ID的统一查找
     */
    public MergedSkill findInProfileByName(Map<Long, MergedSkill> profile, String targetName) {
        if (targetName == null || targetName.isBlank() || profile.isEmpty()) return null;
        String normalized = normalizeSkillName(targetName);

        // 1. 精确标准化匹配（含虚拟ID项）
        for (MergedSkill ms : profile.values()) {
            if (normalized.equals(normalizeSkillName(ms.skillName))) return ms;
        }
        // 2. 包含匹配
        if (normalized.length() >= 3) {
            for (MergedSkill ms : profile.values()) {
                String sn = normalizeSkillName(ms.skillName);
                if (sn.length() >= 3 && (sn.contains(normalized) || normalized.contains(sn))) return ms;
            }
        }
        return null;
    }

    /**
     * 标准化技能名用于比较：
     * 去空格、转小写、常见别名归一化
     */
    public static String normalizeSkillName(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase()
                .replaceAll("\\s+", "")       // Spring Boot → springboot
                .replaceAll("-", "")           // spring-boot → springboot
                .replaceAll("\\.", "")         // .NET → .net
                .replace("c#", "csharp")
                .replace("c++", "cpp");
    }

    /** 获取全部技能列表（带1分钟缓存） */
    private List<Skill> getAllSkills() {
        long now = System.currentTimeMillis();
        if (!skillCache.isEmpty() && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return skillCache;
        }
        List<Skill> fresh = skillMapper.selectList(new LambdaQueryWrapper<Skill>());
        skillCache.clear();
        skillCache.addAll(fresh);
        cacheTimestamp = now;
        return skillCache;
    }

    /** 清除技能缓存（测试用） */
    public void clearCache() {
        skillCache.clear();
        cacheTimestamp = 0;
    }

    /** 解析 mastery 表中的技能名称 */
    private String resolveSkillName(UserSkillMastery m) {
        if (m.getSkillName() != null && !m.getSkillName().isBlank()) {
            return m.getSkillName();
        }
        Skill sk = skillMapper.selectById(m.getSkillId());
        return sk != null ? sk.getName() : "技能#" + m.getSkillId();
    }

    /** 中文等级 → 数值 */
    public static int cnLevelValue(String level) {
        return CN_LEVEL_MAP.getOrDefault(level, 2);
    }

    /** mastery 等级 → 数值（兼中英文） */
    public static int masteryLevelValue(String level) {
        Integer v = CN_LEVEL_MAP.get(level);
        if (v != null) return v;
        return EN_LEVEL_MAP.getOrDefault(level, 2);
    }

    /** 英文等级 → 数值 */
    public static int enLevelValue(String level) {
        return EN_LEVEL_MAP.getOrDefault(level, 2);
    }

    // ──────────────── 内部数据类 ────────────────

    /**
     * 融合后的单项技能画像
     */
    public static class MergedSkill {
        /** 技能名称 */
        public final String skillName;
        /** 掌握等级数值 1~4 */
        public final int level;
        /** 置信度 0.4~0.9 */
        public final double confidence;
        /** 来源：PROFILE / TEST / LEARNING */
        public final String source;

        public MergedSkill(String skillName, int level, double confidence, String source) {
            this.skillName = skillName;
            this.level = level;
            this.confidence = confidence;
            this.source = source;
        }

        @Override
        public String toString() {
            return skillName + "(Lv" + level + "/" + source + "/" + String.format("%.1f", confidence) + ")";
        }
    }
}
