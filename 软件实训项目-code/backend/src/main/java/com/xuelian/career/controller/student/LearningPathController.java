package com.xuelian.career.controller.student;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuelian.career.common.Result;
import com.xuelian.career.dto.response.*;
import com.xuelian.career.entity.*;
import com.xuelian.career.mapper.*;
import com.xuelian.career.service.LearningPathService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 学习路径控制器（学生端）
 * 支持双模式：单岗位/多岗位合并/各岗位独立
 * V5 修正：新增路径统计、技能矩阵、测试生成、regenerate、mastery 接口
 */
@Slf4j
@RestController
@RequestMapping("/api/student/learning")
@RequiredArgsConstructor
public class LearningPathController {

    // Redis不可用时的内存兜底容器（key = "test:session:" + userId + ":" + taskId）
    private final Map<String, List<TestQuestionDTO>> testSessionFallback = new ConcurrentHashMap<>();

    private final LearningPathService learningPathService;
    private final LearningPathMapper pathMapper;
    private final LearningTaskMapper taskMapper;
    private final SkillMapper skillMapper;
    private final UserSkillMasteryMapper masteryMapper;
    private final JobPositionMapper jobPositionMapper;
    private final SkillTestQuestionMapper skillTestQuestionMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    /** POST /api/student/learning/generate?jobId= */
    @PostMapping("/generate")
    public Result<LearningPath> generate(@RequestParam(required = false) Long jobId,
                                         HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        LearningPath path = jobId != null
                ? learningPathService.generatePath(userId, jobId)
                : learningPathService.generatePath(userId);
        return Result.success(path);
    }

    /**
     * POST /api/student/learning/generate/multi
     * 多岗位学习路径生成（双模式）：支持合并路径与单独路径并存
     */
    @PostMapping("/generate/multi")
    public Result<?> generateMulti(@RequestBody Map<String, Object> body,
                                   HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        @SuppressWarnings("unchecked")
        List<Integer> rawIds = (List<Integer>) body.get("jobIds");
        String mode = (String) body.getOrDefault("mode", "MERGED");

        if (rawIds == null || rawIds.isEmpty()) {
            return Result.badRequest("请选择至少一个岗位");
        }

        List<Long> jobIds = rawIds.stream().map(Integer::longValue).toList();

        List<LearningPath> paths = learningPathService.generatePath(userId, jobIds, mode);
        return Result.success(paths);
    }

    /**
     * POST /api/student/learning/regenerate/multi
     * V5 新增：重新生成路径（归档旧路径，清空进度）
     */
    @PostMapping("/regenerate/multi")
    public Result<List<LearningPath>> regenerateMulti(@RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        @SuppressWarnings("unchecked")
        List<Integer> rawIds = (List<Integer>) body.get("jobIds");
        String mode = (String) body.getOrDefault("mode", "MERGED");
        List<Long> jobIds = rawIds.stream().map(Integer::longValue).toList();

        List<LearningPath> paths = learningPathService.regenerateAll(userId, jobIds, mode);
        return Result.success(paths);
    }

    /** GET /api/student/learning/path - 获取当前活跃路径（单条） */
    @GetMapping("/path")
    public Result<LearningPath> getPath(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        LearningPath path = learningPathService.getPath(userId);
        return path != null ? Result.success(path) : Result.success("暂无学习路径", null);
    }

    /** GET /api/student/learning/paths - 获取所有活跃路径 */
    @GetMapping("/paths")
    public Result<List<LearningPath>> getPaths(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        List<LearningPath> paths = learningPathService.getPaths(userId);
        return Result.success(paths);
    }

    /**
     * GET /api/student/learning/path-list-with-stats
     * V5 新增：路径列表带统计信息
     */
    @GetMapping("/path-list-with-stats")
    public Result<List<PathStatsDTO>> getPathListWithStats(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        List<PathStatsDTO> stats = learningPathService.getPathListWithStats(userId);
        return Result.success(stats);
    }

    /** GET /api/student/learning/path/{id}/meta */
    @GetMapping("/path/{id}/meta")
    public Result<Map<String, Object>> getPathMeta(@PathVariable Long id) {
        Map<String, Object> meta = learningPathService.getPathMeta(id);
        return Result.success(meta);
    }

    /**
     * GET /api/student/learning/path/{id}/skills-matrix
     * V5 新增：获取路径的技能-岗位矩阵
     */
    @GetMapping("/path/{id}/skills-matrix")
    public Result<List<SkillsMatrixDTO>> getPathSkillsMatrix(@PathVariable Long id) {
        List<SkillsMatrixDTO> matrix = learningPathService.getPathSkillsMatrix(id);
        return Result.success(matrix);
    }

    /** PUT /api/student/learning/tasks/{id} */
    @PutMapping("/tasks/{id}")
    public Result<Void> updateTaskStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String newStatus = body.get("status");
        learningPathService.updateTaskStatus(id, newStatus);
        return Result.success();
    }

    /** GET /api/student/learning/tasks */
    @GetMapping("/tasks")
    public Result<List<LearningTask>> getTasks(@RequestParam(required = false) Long pathId,
                                                HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (pathId != null) {
            return Result.success(learningPathService.getTasks(userId, pathId));
        }
        return Result.success(learningPathService.getTasks(userId));
    }

    /** GET /api/student/learning/resources?skill=&stage= */
    @GetMapping("/resources")
    public Result<List<LearningResource>> listResources(@RequestParam(required = false) Long skill,
                                                         @RequestParam(required = false) String stage) {
        return Result.success(learningPathService.listResources(skill, stage));
    }

    // ==================== V5 新增：技能测试接口 ====================

    /**
     * POST /api/student/learning/tasks/{id}/test-start
     * V7 重构：纯数据库读取，不使用 AI 生成
     */
    @PostMapping("/tasks/{id}/test-start")
    public Result<List<TestQuestionDTO>> startTest(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        LearningTask task = learningPathService.getTask(id, userId);
        if (task == null) {
            return Result.notFound("任务不存在");
        }

        Skill skill = skillMapper.selectById(task.getSkillId());
        if (skill == null) {
            return Result.error(500, "关联技能不存在");
        }

        List<TestQuestionDTO> questions = new ArrayList<>();

        // ========== 从数据库题库随机获取题目 ==========
        try {
            List<SkillTestQuestion> dbQuestions = skillTestQuestionMapper
                    .selectRandomBySkillAndStage(task.getSkillId(), task.getStage(), 5);
            if (dbQuestions != null && !dbQuestions.isEmpty()) {
                questions = convertFromDbQuestions(dbQuestions);
                log.info("从数据库加载测试题: skillId={}, stage={}, count={}",
                        task.getSkillId(), task.getStage(), questions.size());
            }
        } catch (Exception e) {
            log.warn("从数据库获取测试题失败: skillId={}, stage={}",
                    task.getSkillId(), task.getStage(), e);
        }

        // ========== 数据库无题目时返回通用技术兜底题 ==========
        if (questions.isEmpty()) {
            log.warn("数据库无题目，返回通用技术兜底题: skillId={}, stage={}",
                    task.getSkillId(), task.getStage());
            String skillName = skill.getName();
            // ⚠️ 必须用 ArrayList 而非 List.of()，否则 Redis 序列化时会产生
            //    ImmutableCollections$ListN 类型引用，反序列化时会失败
            questions = new java.util.ArrayList<>(List.of(
                    new TestQuestionDTO(
                            "关于 " + skillName + " 的核心概念，以下说法正确的是？",
                            new java.util.ArrayList<>(List.of("A. 只需要会写代码即可","B. 需要理解其设计思想、核心原理和适用场景","C. 背诵官方文档就能掌握","D. 看视频教程就够了")),
                            "B"
                    ),
                    new TestQuestionDTO(
                            "学习 " + skillName + " 时，遇到报错应该怎么处理？",
                            new java.util.ArrayList<>(List.of("A. 直接放弃或跳过","B. 复制错误信息到搜索引擎/Stack Overflow 查找解决方案","C. 随便改点代码试试","D. 问 AI 但不自己思考")),
                            "B"
                    ),
                    new TestQuestionDTO(
                            "在项目中使用 " + skillName + " 的最佳实践是？",
                            new java.util.ArrayList<>(List.of("A. 不管什么项目都用最新的版本","B. 根据项目需求选择合适版本，先阅读官方文档再动手","C. 只看博客教程不看文档","D. 从 StackOverflow 复制代码直接用")),
                            "B"
                    ),
                    new TestQuestionDTO(
                            "" + skillName + " 在实际开发中最常见的性能问题是什么？如何排查？",
                            new java.util.ArrayList<>(List.of("A. 性能问题不需要关注","B. 使用性能分析工具定位瓶颈，如慢查询日志、火焰图、Profiler等","C. 增加服务器硬件就行","D. 重启服务能解决一切")),
                            "B"
                    ),
                    new TestQuestionDTO(
                            "" + skillName + " 的代码质量保障手段有哪些？",
                            new java.util.ArrayList<>(List.of("A. 只要能运行就行","B. 单元测试、代码审查(CI)、静态分析工具、集成测试","C. 只靠手动测试","D. 发布后再让用户反馈问题")),
                            "B"
                    )
            ));
        }

        // ========== 缓存本次测验 session（供 submit 时验证答案） ==========
        String testSessionKey = "test:session:" + userId + ":" + id;
        try {
            redisTemplate.opsForValue().set(testSessionKey, questions, 30, TimeUnit.MINUTES);
            testSessionFallback.remove(testSessionKey);
        } catch (Exception e) {
            log.warn("Redis不可用，使用内存兜底存储测试session");
            testSessionFallback.put(testSessionKey, questions);
        }

        return Result.success(questions);
    }

    /**
     * POST /api/student/learning/tasks/{id}/test-submit
     * V5 新增：提交测试答案
     */
    @PostMapping("/tasks/{id}/test-submit")
    public Result<TestResultDTO> submitTest(@PathVariable Long id,
                                             @RequestBody Map<String, Map<String, String>> body,
                                             HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        LearningTask task = learningPathService.getTask(id, userId);
        if (task == null) {
            return Result.notFound("任务不存在");
        }

        // 从 Redis 或内存兜底获取缓存的题目
        String testSessionKey = "test:session:" + userId + ":" + id;
        List<TestQuestionDTO> questions = null;

        try {
            Object cached = redisTemplate.opsForValue().get(testSessionKey);
            if (cached != null) {
                // 兼容多种序列化格式：String(JSON)、List、Spring类型包装数组
                if (cached instanceof String) {
                    String jsonStr = (String) cached;
                    // 处理 Spring GenericJackson2JsonSerializer 的 ["type", [...]] 包装格式
                    if (jsonStr.startsWith("[\"java.util.") || jsonStr.startsWith("[\"[Ljava.util.")) {
                        JsonNode node = objectMapper.readTree(jsonStr);
                        if (node.isArray() && node.size() == 2 && node.get(0).isTextual() && node.get(1).isArray()) {
                            jsonStr = node.get(1).toString();
                        }
                    }
                    questions = objectMapper.readValue(jsonStr,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, TestQuestionDTO.class));
                } else if (cached instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> rawList = (List<Object>) cached;
                    // 处理 Spring 类型包装: ["java.util.ArrayList", [actualData]]
                    if (!rawList.isEmpty() && rawList.get(0) instanceof String
                            && ((String) rawList.get(0)).startsWith("java.util.")
                            && rawList.size() > 1 && rawList.get(1) instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Object> innerList = (List<Object>) rawList.get(1);
                        questions = objectMapper.convertValue(innerList,
                                objectMapper.getTypeFactory().constructCollectionType(List.class, TestQuestionDTO.class));
                    } else {
                        questions = objectMapper.convertValue(rawList,
                                objectMapper.getTypeFactory().constructCollectionType(List.class, TestQuestionDTO.class));
                    }
                } else {
                    questions = objectMapper.convertValue(cached,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, TestQuestionDTO.class));
                }
            }
        } catch (Exception e) {
            log.warn("Redis读取测试session失败(userId={}, taskId={}), 尝试内存兜底: {}", userId, id, e.getMessage());
        }

        if (questions == null) {
            questions = testSessionFallback.get(testSessionKey);
        }

        if (questions == null) {
            return Result.error(400, "测试已过期，请重新开始");
        }

        // 获取用户答案（前端传 { "0": "A", "1": "B", ... }）
        Map<String, String> answers = body.get("answers");
        if (answers == null) {
            return Result.badRequest("缺少答案");
        }

        // 计算得分
        int correctCount = 0;
        for (int i = 0; i < questions.size(); i++) {
            String userAnswer = answers.get(String.valueOf(i));
            if (userAnswer != null && userAnswer.equalsIgnoreCase(questions.get(i).getCorrectAnswer())) {
                correctCount++;
            }
        }
        int score = questions.isEmpty() ? 0 : (int) (correctCount * 100.0 / questions.size());

        TestResultDTO result = new TestResultDTO(score, correctCount, questions.size());

        // 通过则更新任务状态和技能掌握度
        if (score >= 60) {
            // 通过 service 更新任务状态（会触发进度互通同步）
            learningPathService.updateTaskStatus(task.getId(), "TEST_PASSED");

            // 更新技能掌握度
            updateOrCreateSkillMastery(userId, task.getSkillId(), result.getNewLevel(), "TEST");
        }

        // 清除本次测验缓存（Redis + 内存兜底）
        try {
            redisTemplate.delete(testSessionKey);
        } catch (Exception e) {
            // silent
        }
        testSessionFallback.remove(testSessionKey);

        return Result.success(result);
    }

    // ==================== V5 新增：技能掌握接口 ====================

    /**
     * GET /api/student/learning/mastered-skills
     * V5 新增：获取用户已掌握的技能列表
     */
    @GetMapping("/mastered-skills")
    public Result<List<UserSkillMastery>> getMasteredSkills(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        List<UserSkillMastery> skills = masteryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserSkillMastery>()
                        .eq(UserSkillMastery::getUserId, userId)
                        .eq(UserSkillMastery::getSource, "TEST")
                        .orderByDesc(UserSkillMastery::getUpdatedAt));
        return Result.success(skills);
    }

    /**
     * POST /api/student/learning/mastered-skills/{skillId}/review
     * V5 新增：记录复习次数
     */
    @PostMapping("/mastered-skills/{skillId}/review")
    public Result<Void> recordReview(@PathVariable Long skillId, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        UserSkillMastery mastery = masteryMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserSkillMastery>()
                        .eq(UserSkillMastery::getUserId, userId)
                        .eq(UserSkillMastery::getSkillId, skillId));
        if (mastery != null) {
            mastery.setReviewCount((mastery.getReviewCount() != null ? mastery.getReviewCount() : 0) + 1);
            mastery.setLastReviewedAt(LocalDateTime.now());
            masteryMapper.updateById(mastery);
        }
        return Result.success();
    }

    // ==================== 辅助方法 ====================

    /**
     * 更新或创建技能掌握记录
     */
    private void updateOrCreateSkillMastery(Long userId, Long skillId, String level, String source) {
        UserSkillMastery existing = masteryMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserSkillMastery>()
                        .eq(UserSkillMastery::getUserId, userId)
                        .eq(UserSkillMastery::getSkillId, skillId));

        Skill skill = skillMapper.selectById(skillId);
        String skillName = skill != null ? skill.getName() : "未知技能";

        if (existing != null) {
            // 只有新等级更高才升级
            if (shouldUpgradeLevel(existing.getLevel(), level)) {
                existing.setLevel(level);
            }
            existing.setSource(source);
            existing.setUpdatedAt(LocalDateTime.now());
            masteryMapper.updateById(existing);
        } else {
            UserSkillMastery mastery = new UserSkillMastery();
            mastery.setUserId(userId);
            mastery.setSkillId(skillId);
            mastery.setSkillName(skillName);
            mastery.setLevel(level);
            mastery.setSource(source);
            mastery.setFirstMasteredAt(LocalDateTime.now());
            mastery.setReviewCount(0);
            mastery.setCreatedAt(LocalDateTime.now());
            masteryMapper.insert(mastery);
        }
    }

    /**
     * 判断是否应该升级等级
     */
    private boolean shouldUpgradeLevel(String currentLevel, String newLevel) {
        List<String> order = List.of("BASIC", "INTERMEDIATE", "ADVANCED", "EXPERT");
        int currentIdx = order.indexOf(currentLevel);
        int newIdx = order.indexOf(newLevel);
        return newIdx > currentIdx;
    }

    /**
     * 将数据库题目转换为 DTO 格式
     */
    private List<TestQuestionDTO> convertFromDbQuestions(List<SkillTestQuestion> dbQuestions) {
        List<TestQuestionDTO> result = new ArrayList<>();
        for (SkillTestQuestion q : dbQuestions) {
            try {
                List<String> options = objectMapper.readValue(q.getOptions(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                result.add(new TestQuestionDTO(q.getQuestion(), options, q.getCorrectAnswer()));
            } catch (JsonProcessingException e) {
                log.warn("解析数据库题目 options JSON 失败: id={}", q.getId(), e);
            }
        }
        return result;
    }
}
