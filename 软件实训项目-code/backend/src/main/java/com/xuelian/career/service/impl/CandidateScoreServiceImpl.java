package com.xuelian.career.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuelian.career.config.RecommendConfig;
import com.xuelian.career.dto.response.EnterpriseRecommendResponse.*;
import com.xuelian.career.entity.*;
import com.xuelian.career.mapper.*;
import com.xuelian.career.service.CandidateScoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 候选人评分引擎实现 - 四维度加权评分（V2 升级版）
 * <p>
 * V2 变更：
 * <ul>
 *   <li>技能匹配：多源融合（自报+测试+学习），ID 精确匹配 + 置信度加权 + 岗位技能权重</li>
 *   <li>学习掌握：合并原 学习进度 + 学习成果 为单一维度</li>
 *   <li>新权重：SKILL(40%) + ASSESSMENT(20%) + MASTERY(25%) + BASIC(15%)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateScoreServiceImpl implements CandidateScoreService {

    private final CareerProfileMapper profileMapper;
    private final AssessmentResultMapper assessmentResultMapper;
    private final LearningTaskMapper learningTaskMapper;
    private final LearningResultMapper learningResultMapper;
    private final UserMapper userMapper;
    private final SkillMapper skillMapper;
    private final RecommendConfig config;
    private final ObjectMapper objectMapper;
    private final SkillMergeService skillMergeService;

    @Override
    public CandidateScore compute(Long userId, List<SkillRequirement> requiredSkills,
                                   String positionTitle, FilterInfo filters) {
        User user = userMapper.selectById(userId);
        CareerProfile profile = profileMapper.selectOne(
                new LambdaQueryWrapper<CareerProfile>().eq(CareerProfile::getUserId, userId));
        List<AssessmentResult> assessmentHistory = assessmentResultMapper.selectList(
                new LambdaQueryWrapper<AssessmentResult>().eq(AssessmentResult::getUserId, userId)
                        .orderByDesc(AssessmentResult::getCreatedAt).last("LIMIT 5"));
        AssessmentResult assessment = assessmentHistory.isEmpty() ? null : assessmentHistory.get(0);
        List<LearningTask> tasks = learningTaskMapper.selectList(
                new LambdaQueryWrapper<LearningTask>().eq(LearningTask::getUserId, userId));

        // 构建多源融合技能画像
        Map<Long, SkillMergeService.MergedSkill> mergedProfile = skillMergeService.buildMergedSkillProfile(userId);

        // 四维度评分
        SkillMatchResult skillResult = calcSkillMatch(mergedProfile, requiredSkills);
        double assessmentScore = calcAssessmentMatch(assessment, assessmentHistory, positionTitle);
        double masteryScore = calcMasteryScore(tasks, userId);
        double basicScore = calcBasicMatch(profile, user, filters);

        // 综合加权
        double total = weightSkill() * skillResult.score
                     + weightAssessment() * assessmentScore
                     + weightMastery() * masteryScore
                     + weightBasic() * basicScore;

        return buildResult(user, profile, userId, total, skillResult, assessmentScore,
                masteryScore, basicScore);
    }

    /**
     * 新增：按岗位技能要求（含 skillId + weight）匹配
     */
    public CandidateScore computeForJob(Long userId, List<JobSkillRequirement> jobSkills,
                                         String positionTitle, FilterInfo filters) {
        User user = userMapper.selectById(userId);
        CareerProfile profile = profileMapper.selectOne(
                new LambdaQueryWrapper<CareerProfile>().eq(CareerProfile::getUserId, userId));
        List<AssessmentResult> assessmentHistory = assessmentResultMapper.selectList(
                new LambdaQueryWrapper<AssessmentResult>().eq(AssessmentResult::getUserId, userId)
                        .orderByDesc(AssessmentResult::getCreatedAt).last("LIMIT 5"));
        AssessmentResult assessment = assessmentHistory.isEmpty() ? null : assessmentHistory.get(0);
        List<LearningTask> tasks = learningTaskMapper.selectList(
                new LambdaQueryWrapper<LearningTask>().eq(LearningTask::getUserId, userId));

        // 构建多源融合技能画像
        Map<Long, SkillMergeService.MergedSkill> mergedProfile = skillMergeService.buildMergedSkillProfile(userId);

        // SKILL：按岗位技能要求精确匹配
        SkillMatchResult skillResult = calcSkillMatchByJob(mergedProfile, jobSkills);
        double assessmentScore = calcAssessmentMatch(assessment, assessmentHistory, positionTitle);
        double masteryScore = calcMasteryScore(tasks, userId);
        double basicScore = calcBasicMatch(profile, user, filters);

        double total = weightSkill() * skillResult.score
                     + weightAssessment() * assessmentScore
                     + weightMastery() * masteryScore
                     + weightBasic() * basicScore;

        return buildResult(user, profile, userId, total, skillResult, assessmentScore,
                masteryScore, basicScore);
    }

    // ==================== 技能匹配（40% — 多源融合版）====================

    /**
     * 基于融合画像 + skillId 的名称映射匹配（兼容 AI 推荐的 SkillRequirement）
     * V2: 使用 SkillMergeService 的模糊名称匹配，解决名称不完全一致导致匹配失败的问题
     */
    private SkillMatchResult calcSkillMatch(Map<Long, SkillMergeService.MergedSkill> profile,
                                             List<SkillRequirement> requiredSkills) {
        if (requiredSkills == null || requiredSkills.isEmpty()) {
            return new SkillMatchResult(50.0, List.of(), List.of());
        }

        double totalWeight = requiredSkills.size();
        double earned = 0;
        List<String> matched = new ArrayList<>();
        List<String> gaps = new ArrayList<>();

        for (SkillRequirement req : requiredSkills) {
            String name = req.getSkillName();
            int reqLevel = SkillMergeService.cnLevelValue(req.getRequiredLevel());

            // V2: 使用 SkillMergeService 的多策略模糊查找
            Skill skill = skillMergeService.findSkillByName(name);
            Long skillId = skill != null ? skill.getId() : null;

            SkillMergeService.MergedSkill ms = (skillId != null) ? profile.get(skillId) : null;

            if (ms != null) {
                double contribution = calcLevelContribution(ms.level, reqLevel, ms.confidence);
                earned += contribution;
                // V2.1: 降低达标阈值到 0.25
                if (contribution >= 0.20) matched.add(name + "(" + ms.source + ")");
                else gaps.add(name);
            } else {
                // V2: 用融合引擎的模糊名称查找作为最终兜底（支持虚拟ID）
                SkillMergeService.MergedSkill fuzzy = skillMergeService.findInProfileByName(profile, name);
                if (fuzzy != null) {
                    double contribution = calcLevelContribution(fuzzy.level, reqLevel, fuzzy.confidence);
                    earned += contribution;
                    if (contribution >= 0.20) matched.add(name + "(模糊/" + fuzzy.source + ")");
                    else gaps.add(name);
                } else {
                    gaps.add(name);
                }
            }
        }

        double score = totalWeight > 0 ? (earned / totalWeight) * 100 : 50;

        // V2: 诊断日志（仅当缺口较多时输出，避免刷屏）
        if (!gaps.isEmpty()) {
            log.debug("[SkillMatch] 技能匹配: 命中={}, 缺口={}, 得分={}",
                    matched.size(), gaps.size(), round(score));
        }

        return new SkillMatchResult(Math.min(100, score), matched, gaps);
    }

    /**
     * 按岗位技能要求精确匹配（ID 匹配 + 岗位权重）
     * V2: ID未命中时增加模糊名称兜底
     */
    private SkillMatchResult calcSkillMatchByJob(Map<Long, SkillMergeService.MergedSkill> profile,
                                                  List<JobSkillRequirement> jobSkills) {
        if (jobSkills == null || jobSkills.isEmpty()) {
            return new SkillMatchResult(50.0, List.of(), List.of());
        }

        double totalWeight = 0;
        double earned = 0;
        List<String> matched = new ArrayList<>();
        List<String> gaps = new ArrayList<>();

        for (JobSkillRequirement req : jobSkills) {
            String skillName = resolveSkillName(req.getSkillId());
            int reqLevel = SkillMergeService.cnLevelValue(
                    req.getRequiredLevel() != null ? req.getRequiredLevel() : "了解");
            double jobWeight = req.getWeight() != null ? req.getWeight() : 1.0;
            totalWeight += jobWeight;

            SkillMergeService.MergedSkill ms = profile.get(req.getSkillId());
            if (ms != null) {
                double contribution = calcLevelContribution(ms.level, reqLevel, ms.confidence);
                earned += contribution * jobWeight;
                // V2.1: 阈值0.20——有技能就尽量展示为匹配
                if (contribution >= 0.20) matched.add(skillName + "(" + ms.source + ")");
                else gaps.add(skillName);
            } else {
                // V2: ID没命中，尝试按技能名在画像中模糊查找
                SkillMergeService.MergedSkill fuzzy = skillMergeService.findInProfileByName(profile, skillName);
                if (fuzzy != null) {
                    double contribution = calcLevelContribution(fuzzy.level, reqLevel, fuzzy.confidence);
                    earned += contribution * jobWeight;
                    if (contribution >= 0.20) matched.add(skillName + "(模糊/" + fuzzy.source + ")");
                    else gaps.add(skillName);
                } else {
                    gaps.add(skillName);
                }
            }
        }

        double score = totalWeight > 0 ? (earned / totalWeight) * 100 : 50;

        if (!gaps.isEmpty()) {
            log.debug("[SkillMatchByJob] 岗位技能匹配: 总数={}, 命中={}, 缺口={}, 得分={}",
                    jobSkills.size(), matched.size(), gaps.size(), round(score));
        }

        return new SkillMatchResult(Math.min(100, score), matched, gaps);
    }

    /** 等级匹配贡献度（含置信度） */
    // V2.2: 调高等级差距折扣系数，避免有技能但因等级差被过度惩罚
    private double calcLevelContribution(int stuLevel, int reqLevel, double confidence) {
        if (stuLevel >= reqLevel) return 1.0 * confidence;
        if (stuLevel == reqLevel - 1) return 0.8 * confidence;   // 差1级打8折
        return 0.5 * confidence;                                  // 差2级+打5折
    }

    /** 通过 skillId 获取技能名 */
    private String resolveSkillName(Long skillId) {
        Skill sk = skillMapper.selectById(skillId);
        return sk != null ? sk.getName() : "技能#" + skillId;
    }

    // ==================== 测评适配（20%）====================

    private double calcAssessmentMatch(AssessmentResult latest, List<AssessmentResult> history, String positionTitle) {
        if (latest == null) return 40.0;

        Map<String, Double> weights = config.getDimensionWeights(positionTitle);
        if (weights == null || weights.isEmpty()) return 50;

        double latestScore = nvl(latest.getProgrammingScore()) * weights.getOrDefault("programming", 0.2)
                           + nvl(latest.getLogicScore()) * weights.getOrDefault("logic", 0.2)
                           + nvl(latest.getProductScore()) * weights.getOrDefault("product", 0.2)
                           + nvl(latest.getTechScore()) * weights.getOrDefault("tech", 0.2)
                           + nvl(latest.getCommunicationScore()) * weights.getOrDefault("communication", 0.2);

        double trendScore = calcTrendScore(history);

        double levelScore = 50;
        if (latest.getTotalScore() != null) {
            if (latest.getTotalScore() >= 90) levelScore = 95;
            else if (latest.getTotalScore() >= 80) levelScore = 80;
            else if (latest.getTotalScore() >= 70) levelScore = 65;
            else if (latest.getTotalScore() >= 60) levelScore = 50;
            else levelScore = 35;
        }

        return Math.min(100, latestScore * 0.60 + trendScore * 0.20 + levelScore * 0.20);
    }

    private double calcTrendScore(List<AssessmentResult> history) {
        if (history == null || history.size() < 2) return 50;
        List<AssessmentResult> sorted = history.stream()
                .sorted(Comparator.comparing(AssessmentResult::getCreatedAt))
                .collect(Collectors.toList());
        if (sorted.size() < 2) return 50;

        double trend = 0;
        int pairs = 0;
        for (int i = 1; i < sorted.size(); i++) {
            Double prev = sorted.get(i - 1).getTotalScore();
            Double curr = sorted.get(i).getTotalScore();
            if (prev != null && curr != null) {
                trend += (curr - prev);
                pairs++;
            }
        }
        if (pairs == 0) return 50;
        return Math.min(100, Math.max(20, 50 + (trend / pairs) * 2));
    }

    // ==================== 学习掌握（25% — 合并学习进度 + 学习成果）====================

    /**
     * 综合学习掌握分 = 学习进度(40%) + 学习成果测评(60%)
     */
    private double calcMasteryScore(List<LearningTask> tasks, Long userId) {
        double progressScore = calcLearningProgress(tasks);
        double resultScore = calcLearningResultScore(userId);
        return progressScore * 0.40 + resultScore * 0.60;
    }

    /** 学习进度评分 */
    private double calcLearningProgress(List<LearningTask> tasks) {
        if (tasks == null || tasks.isEmpty()) return 30.0;

        Map<String, Double> stageWeights = Map.of(
                "INTERVIEW", 1.5, "PROJECT", 1.3, "FRAMEWORK", 1.0, "BASIC", 0.7
        );
        java.time.LocalDate today = java.time.LocalDate.now();
        double weightedSum = 0, totalWeight = 0;

        for (LearningTask t : tasks) {
            double stageW = stageWeights.getOrDefault(t.getStage(), 1.0);
            totalWeight += stageW;
            if ("COMPLETED".equals(t.getStatus())) {
                weightedSum += stageW;
            } else if ("IN_PROGRESS".equals(t.getStatus())) {
                boolean overdue = t.getDueDate() != null && t.getDueDate().isBefore(today);
                weightedSum += stageW * (overdue ? 0.25 : 0.50);
            }
        }

        double ratio = totalWeight > 0 ? (weightedSum / totalWeight) : 0;
        return Math.min(100, Math.max(10, ratio * 100));
    }

    /** 学习成果测评评分 */
    private double calcLearningResultScore(Long userId) {
        List<LearningResult> results = learningResultMapper.selectList(
                new LambdaQueryWrapper<LearningResult>()
                        .eq(LearningResult::getUserId, userId)
                        .orderByDesc(LearningResult::getCreatedAt));

        if (results == null || results.isEmpty()) return 25.0;

        Map<String, Double> stageWeights = Map.of(
                "FINAL", 1.4, "PROJECT", 1.3, "INTERVIEW", 1.2, "FRAMEWORK", 1.0, "BASIC", 0.8
        );
        double totalWeight = 0, weightedSum = 0;
        for (LearningResult r : results) {
            double w = stageWeights.getOrDefault(r.getStage(), 1.0);
            totalWeight += w;
            weightedSum += w * nvl(r.getTotalScore());
        }
        double stageWeightedScore = totalWeight > 0 ? weightedSum / totalWeight : 50;

        LearningResult latest = results.get(0);
        double latestScore = nvl(latest.getTotalScore());

        long passed = results.stream().filter(r -> r.getPassed() != null && r.getPassed() == 1).count();
        double passRate = results.size() > 0 ? (double) passed / results.size() * 100 : 0;

        return Math.min(100, stageWeightedScore * 0.50 + latestScore * 0.30 + passRate * 0.20);
    }

    // ==================== 基础匹配（15%）====================

    private double calcBasicMatch(CareerProfile profile, User user, FilterInfo filters) {
        double eduScore = calcEducationMatch(profile, user, filters);
        double cityScore = calcCityMatch(profile, filters);
        double majorScore = calcMajorMatch(profile, user);
        return eduScore * 0.3 + cityScore * 0.3 + 100 * 0.2 + majorScore * 0.2;
    }

    private double calcEducationMatch(CareerProfile profile, User user, FilterInfo filters) {
        String reqEdu = filters != null ? filters.getExpectedEducation() : null;
        String stuEdu = (profile != null && profile.getEducation() != null)
                ? profile.getEducation() : (user.getEducation() != null ? user.getEducation() : "");
        if (reqEdu == null || reqEdu.isBlank()) return 100;
        if (stuEdu.isBlank()) return 70;
        int gap = Math.abs(eduLevel(reqEdu) - eduLevel(stuEdu));
        if (gap == 0) return 100;
        if (gap == 1) return 70;
        return 30;
    }

    private int eduLevel(String edu) {
        String e = edu.toLowerCase();
        if (e.contains("博士")) return 5;
        if (e.contains("硕士")) return 4;
        if (e.contains("本科") || e.contains("bachelor")) return 3;
        if (e.contains("大专") || e.contains("专科")) return 2;
        return 1;
    }

    private double calcCityMatch(CareerProfile profile, FilterInfo filters) {
        String reqCity = filters != null ? filters.getExpectedCity() : null;
        if (reqCity == null || reqCity.isBlank()) return 100;
        String stuCity = profile != null ? profile.getExpectedCity() : "";
        if (stuCity == null || stuCity.isBlank()) return 70;
        if (stuCity.contains(reqCity) || reqCity.contains(stuCity)) return 100;
        return 30;
    }

    private double calcMajorMatch(CareerProfile profile, User user) {
        String major = (profile != null && profile.getMajor() != null)
                ? profile.getMajor() : (user.getMajor() != null ? user.getMajor() : "");
        if (major.isBlank()) return 60;
        String lower = major.toLowerCase();
        if (containsAny(lower, "计算机", "软件工程", "人工智能", "computer", "software")) return 100;
        if (containsAny(lower, "电子", "通信", "信息", "electronic")) return 80;
        if (containsAny(lower, "数学", "统计", "math")) return 75;
        if (containsAny(lower, "产品", "设计", "design", "交互")) return 70;
        return 40;
    }

    // ==================== 辅助方法 ====================

    private CandidateScore buildResult(User user, CareerProfile profile, Long userId,
                                        double total, SkillMatchResult skillResult,
                                        double assessmentScore, double masteryScore, double basicScore) {
        AssessmentResult assessment = assessmentResultMapper.selectOne(
                new LambdaQueryWrapper<AssessmentResult>().eq(AssessmentResult::getUserId, userId)
                        .orderByDesc(AssessmentResult::getCreatedAt).last("LIMIT 1"));

        CandidateScore result = new CandidateScore();
        result.setUserId(userId);
        result.setUsername(user.getUsername() != null ? user.getUsername() : user.getRealName());
        result.setEducation(profile != null ? profile.getEducation()
                : (user.getEducation() != null ? user.getEducation() : "未知"));
        result.setSchool(profile != null ? profile.getSchool()
                : (user.getSchool() != null ? user.getSchool() : ""));
        result.setMajor(profile != null ? profile.getMajor()
                : (user.getMajor() != null ? user.getMajor() : ""));
        result.setMatchScore(round(total));
        result.setMatchLevel(getLevel(total));
        result.setSkillScore(round(skillResult.score));
        result.setAssessmentScore(round(assessmentScore));
        result.setLearningScore(round(masteryScore));        // 映射到旧字段（学习掌握）
        result.setLearningResultScore(0.0);                   // 已合并，归零
        result.setBasicScore(round(basicScore));
        result.setMatchedSkills(skillResult.matched);
        result.setGapSkills(skillResult.gaps);
        result.setRecommendReason(buildReason(skillResult, assessment, masteryScore));
        return result;
    }

    private String buildReason(SkillMatchResult skillResult, AssessmentResult assessment, double masteryScore) {
        StringBuilder sb = new StringBuilder();
        if (!skillResult.matched.isEmpty()) {
            sb.append("在").append(String.join("、",
                    skillResult.matched.stream().limit(3).collect(Collectors.toList())));
            sb.append("方面与岗位要求较匹配");
        }
        if (!skillResult.gaps.isEmpty()) {
            if (sb.length() > 0) sb.append("，");
            sb.append("建议关注").append(String.join("、",
                    skillResult.gaps.stream().limit(2).collect(Collectors.toList())));
            sb.append("的培养");
        }
        if (masteryScore >= 80) {
            sb.append("，学习掌握度优秀");
        }
        if (sb.length() == 0) sb.append("综合匹配度较高");
        return sb.toString();
    }

    private String getLevel(double score) {
        if (score >= 85) return "非常匹配";
        if (score >= 70) return "比较匹配";
        if (score >= 60) return "一般匹配";
        if (score >= 45) return "部分匹配";
        return "不太匹配";
    }

    private double round(double v) { return Math.round(v * 10.0) / 10.0; }

    private double nvl(Double v) { return v != null ? v : 50; }

    private boolean containsAny(String s, String... keywords) {
        for (String kw : keywords) if (s.contains(kw)) return true;
        return false;
    }

    // ==================== V2 权重读取 ====================

    private double weightSkill()      { return config.getScoring().getWeightSkill(); }
    private double weightAssessment() { return config.getScoring().getWeightAssessment(); }
    private double weightMastery()    { return config.getScoring().getWeightMastery(); }
    private double weightBasic()      { return config.getScoring().getWeightBasic(); }

    // ==================== 内部类 ====================

    private static class SkillMatchResult {
        final double score;
        final List<String> matched;
        final List<String> gaps;
        SkillMatchResult(double score, List<String> matched, List<String> gaps) {
            this.score = score;
            this.matched = matched;
            this.gaps = gaps;
        }
    }
}
