package com.xuelian.career.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuelian.career.dto.response.EnterpriseRecommendResponse.*;
import com.xuelian.career.service.DeepSeekService;
import com.xuelian.career.service.ProjectParseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 项目需求解析服务实现 - 调用 DeepSeek 解析项目描述为岗位+技能建议
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectParseServiceImpl implements ProjectParseService {

    private final DeepSeekService deepSeekService;
    private final ObjectMapper objectMapper;

    /** 项目解析系统提示词 */
    private static final String PARSE_SYSTEM_PROMPT =
        "你是一位资深技术招聘顾问。请严格根据项目需求描述，分析所需技术岗位和技能要求。" +
        "必须只返回 JSON 格式，不要包含任何其他文字。" +
        "重要：不同项目必须产出差异化的岗位和技能组合，避免总是返回相同的结果。";

    /** 项目解析用户提示词模板 */
    private static final String PARSE_PROMPT_TEMPLATE =
        "项目需求：%s\n\n" +
        "请以 JSON 格式返回分析结果（严格保持以下结构）：\n" +
        "{\n" +
        "  \"projectSummary\": \"用一句话概括该项目核心目标和特点（体现项目独特性）\",\n" +
        "  \"positions\": [\n" +
        "    {\n" +
        "      \"positionTitle\": \"岗位名称（根据项目实际需求选择最贴切的，如 后端开发工程师/前端开发工程师/测试工程师/数据分析师等）\",\n" +
        "      \"skillRequirements\": [\n" +
        "        {\"skillName\": \"技能名称\", \"requiredLevel\": \"精通/熟练/掌握/了解\"}\n" +
        "      ],\n" +
        "      \"headcount\": 需求人数建议（数字）\n" +
        "    }\n" +
        "  ]\n" +
        "}\n\n" +
        "关键要求：\n" +
        "1. 输出2-5个岗位，每个岗位3-8个核心技能\n" +
        "2. 技能等级使用精通/熟练/掌握/了解\n" +
        "3. 技能名称使用行业通用名称\n" +
        "4. 【差异化】如果项目涉及'管理系统/CRUD/审批'则重点输出Spring Security/权限管理/RBAC等技能；\n" +
        "   如果项目涉及'高并发/实时/直播'则重点输出Netty/WebSocket/消息队列等技能；\n" +
        "   如果项目涉及'数据分析/AI'则重点输出Python/Pandas/机器学习等技能；\n" +
        "   如果项目涉及'移动端/小程序'则重点输出Flutter/跨平台/原生开发等技能。\n" +
        "5. 不同类型的项目必须给出明显不同的技能侧重点，不要千篇一律都是Java/Spring Boot/MySQL";

    // ---- 兜底关键词匹配表（V2: 增加更多项目类型覆盖） ----
    private static final List<KeywordRule> FALLBACK_RULES = Arrays.asList(
        new KeywordRule(
            Arrays.asList("商城", "电商", "支付", "订单", "购物车", "商品"),
            Arrays.asList(
                pos("后端开发工程师", java("精通", "Spring Boot", "熟练", "MySQL", "熟练", "Redis", "掌握", "微服务", "了解"), 3),
                pos("前端开发工程师", skills("Vue", "熟练", "React", "掌握", "JavaScript", "精通", "CSS", "熟练", "Element Plus", "掌握"), 2),
                pos("测试工程师", skills("JUnit", "掌握", "Selenium", "掌握", "Postman", "熟练", "性能测试", "了解"), 1)
            )
        ),
        new KeywordRule(
            Arrays.asList("数据", "报表", "分析", "可视化", "数仓", "大屏"),
            Arrays.asList(
                pos("数据分析师", skills("Python", "熟练", "SQL", "精通", "Pandas", "掌握", "ECharts", "掌握", "Spark", "了解"), 2),
                pos("后端开发工程师", java("熟练", "Spring Boot", "熟练", "MySQL", "精通", "Redis", "掌握"), 2)
            )
        ),
        new KeywordRule(
            Arrays.asList("APP", "移动", "Android", "iOS", "小程序", "Flutter", "React Native"),
            Arrays.asList(
                pos("移动端开发工程师", skills("Flutter", "熟练", "Dart", "掌握", "Java/Kotlin", "掌握", "Swift", "了解", "RESTful API", "掌握"), 2),
                pos("后端开发工程师", java("熟练", "Spring Boot", "熟练", "MySQL", "熟练"), 1)
            )
        ),
        new KeywordRule(
            Arrays.asList("后台", "管理", "CRUD", "审批", "权限", "RBAC"),
            Arrays.asList(
                pos("后端开发工程师", java("精通", "Spring Boot", "精通", "MySQL", "熟练", "Redis", "掌握", "Spring Security", "精通"), 2),
                pos("前端开发工程师", skills("Vue", "熟练", "Element Plus", "熟练", "TypeScript", "掌握", "权限管理", "了解"), 1)
            )
        ),
        new KeywordRule(
            Arrays.asList("推荐", "算法", "机器学习", "AI", "NLP", "深度学习", "神经网络"),
            Arrays.asList(
                pos("算法工程师", skills("Python", "精通", "TensorFlow", "熟练", "PyTorch", "熟练", "机器学习", "精通", "深度学习", "掌握"), 2),
                pos("后端开发工程师", java("熟练", "Spring Boot", "熟练", "MySQL", "掌握"), 1)
            )
        ),
        new KeywordRule(
            Arrays.asList("直播", "视频", "IM", "WebRTC", "实时", "流媒体"),
            Arrays.asList(
                pos("后端开发工程师", skills("Java", "精通", "Netty", "熟练", "WebSocket", "掌握", "Spring Boot", "熟练", "Redis", "精通", "消息队列", "掌握"), 3),
                pos("前端开发工程师", skills("Vue", "熟练", "WebRTC", "掌握", "WebSocket", "掌握", "HLS", "了解"), 1)
            )
        ),
        new KeywordRule(
            Arrays.asList("测试", "质量", "自动化", "CI/CD"),
            Arrays.asList(
                pos("测试工程师", skills("Selenium", "熟练", "JUnit", "掌握", "JMeter", "掌握", "Jenkins", "了解", "Docker", "了解", "自动化测试框架", "掌握"), 3)
            )
        ),
        new KeywordRule(
            Arrays.asList("运营", "活动", "营销", "用户增长", "新媒体"),
            Arrays.asList(
                pos("运营专员", skills("SQL", "掌握", "Excel", "精通", "Python", "了解", "数据分析", "掌握", "文案策划", "熟练"), 2)
            )
        ),
        // V2 新增：高并发/分布式项目
        new KeywordRule(
            Arrays.asList("高并发", "分布式", "微服务", "集群", "负载均衡", "缓存", "消息队列", "MQ", "Kafka", "RabbitMQ"),
            Arrays.asList(
                pos("后端开发工程师", java("精通", "Spring Cloud", "精通", "Redis", "精通", "MySQL", "熟练",
                        "消息队列", "熟练", "分布式事务", "掌握", "Docker", "掌握", "Kubernetes", "了解"), 3),
                pos("运维/DevOps工程师", skills("Docker", "精通", "Kubernetes", "熟练", "Linux", "精通",
                        "CI/CD", "掌握", "Nginx", "熟练", "Shell脚本", "掌握"), 1)
            )
        ),
        // V2 新增：物联网/嵌入式
        new KeywordRule(
            Arrays.asList("物联网", "IoT", "嵌入式", "传感器", "硬件", "MQTT", "边缘计算"),
            Arrays.asList(
                pos("嵌入式开发工程师", skills("C/C++", "精通", "Linux", "熟练", "MQTT", "掌握", "通信协议", "熟练", "RTOS", "了解"), 2),
                pos("后端开发工程师", java("熟练", "Spring Boot", "掌握", "MQTT", "熟练", "时序数据库", "了解"), 1)
            )
        ),
        // V2 新增：区块链/Web3
        new KeywordRule(
            Arrays.asList("区块链", "Web3", "智能合约", "去中心化", "Solidity"),
            Arrays.asList(
                pos("区块链开发工程师", skills("Solidity", "精通", "Web3.js", "熟练", "Go", "掌握", "以太坊", "熟练", "智能合约", "精通"), 2),
                pos("后端开发工程师", java("掌握", "Spring Boot", "熟练", "密码学", "了解"), 1)
            )
        )
    );

    @Override
    public ParseResult parseProject(String projectDescription) {
        // ── V3 预检：AI 判断输入是否是一个软件项目描述 ──
        String validationResult = validateIsProject(projectDescription);
        if ("NOT_PROJECT".equals(validationResult)) {
            log.warn("AI 预检：输入内容不是有效的软件项目描述，拒绝解析");
            return ParseResult.invalid("输入内容不是有效的软件项目需求描述，请输入真实项目需求（如\"电商平台\"、\"后台管理系统\"、\"数据分析大屏\"等）");
        }

        // 尝试 AI 解析
        try {
            if (deepSeekService.isAvailable()) {
                String prompt = String.format(PARSE_PROMPT_TEMPLATE, projectDescription);
                String response = deepSeekService.callAPI(PARSE_SYSTEM_PROMPT, prompt);
                Map<String, Object> parsed = deepSeekService.parseJSONResponse(response);
                if (parsed != null && parsed.containsKey("positions")) {
                    ParseResult result = buildFromMap(parsed);
                    log.info("AI 项目解析成功: positions={}, summary={}", result.getPositions().size(),
                            result.getProjectSummary());
                    return result;
                }
            }
        } catch (Exception e) {
            log.warn("AI 项目解析失败，降级到关键词匹配: {}", e.getMessage());
        }

        // 兜底：关键词匹配（如果无任何关键词命中，也说明不是项目）
        KeywordMatchResult kwResult = fallbackParseWithValidation(projectDescription);
        if (kwResult.isNotProject) {
            return ParseResult.invalid("输入内容缺少项目特征关键词，请提供更具体的项目需求描述");
        }
        log.info("兜底关键词解析完成: score={}, positions={}", kwResult.score, kwResult.result.getPositions().size());
        return kwResult.result;
    }

    /**
     * V3: AI 预检 — 用极简 Prompt 判断输入是否是软件项目描述
     * @return "PROJECT" | "NOT_PROJECT" | "UNKNOWN"
     */
    private String validateIsProject(String text) {
        try {
            if (!deepSeekService.isAvailable()) {
                // AI 不可用，用关键词规则兜底校验
                return keywordProjectCheck(text) ? "PROJECT" : "NOT_PROJECT";
            }

            String checkPrompt = String.format(
                "请判断以下文本是否属于软件/互联网/IT 项目需求描述（如需要开发某个系统、平台、功能模块、应用等）。\n" +
                "如果是，回复 JSON: {\"isProject\":true,\"reason\":\"简要说明\"}\n" +
                "如果不是（如闲聊、问问题、纯技术概念罗列、无关文本），回复 JSON: {\"isProject\":false,\"reason\":\"简要说明\"}\n\n" +
                "文本：%s\n\n" +
                "只返回 JSON，不要包含其他文字。", text);

            String systemPrompt = "你是一个文本分类器，只判断输入文本是否为软件项目需求描述。只返回 JSON。";
            String response = deepSeekService.callAPI(systemPrompt, checkPrompt);

            if (response != null) {
                Map<String, Object> parsed = deepSeekService.parseJSONResponse(response);
                if (parsed != null) {
                    Object isProject = parsed.get("isProject");
                    if (Boolean.FALSE.equals(isProject)) {
                        log.info("AI 预检: 判定为非项目描述, reason={}", parsed.get("reason"));
                        return "NOT_PROJECT";
                    }
                    if (Boolean.TRUE.equals(isProject)) {
                        log.info("AI 预检: 判定为项目描述, reason={}", parsed.get("reason"));
                        return "PROJECT";
                    }
                }
            }
        } catch (Exception e) {
            log.debug("AI 项目预检异常, 降级关键词校验: {}", e.getMessage());
        }
        // AI 预检失败时，用关键词校验
        return keywordProjectCheck(text) ? "PROJECT" : "NOT_PROJECT";
    }

    /**
     * V3: 兜底关键词校验 — 必须有项目特征关键词才判定为项目
     * 项目特征关键词：包含具体功能/业务场景，而非单纯的技能罗列
     */
    private boolean keywordProjectCheck(String text) {
        String lower = text.toLowerCase();
        // 项目特征关键词（必须包含以下至少一个）
        String[] projectKeywords = {
            "项目", "系统", "平台", "开发", "功能", "需求", "设计",
            "模块", "后台", "前端", "后端", "实现", "搭建", "构建",
            "架构", "方案", "接口", "服务", "管理", "处理", "支持",
            "对接", "集成", "部署", "上线", "数据", "流程", "用户",
            "页面", "界面", "app", "应用", "小程序", "商城", "电商",
            "直播", "支付", "订单", "审批", "报表", "大屏", "推荐",
            "监控", "采集", "存储", "查询", "分析", "可视化"
        };

        int hitCount = 0;
        for (String kw : projectKeywords) {
            if (lower.contains(kw.toLowerCase())) hitCount++;
            if (hitCount >= 3) return true; // 命中3个以上才算项目描述
        }
        // 如果文本足够长（>100字）且包含技术栈词汇，也可能是项目描述
        if (text.length() > 100) {
            int techHits = 0;
            for (String kw : new String[]{"java", "python", "vue", "react", "spring", "mysql", "redis", "docker",
                    "kafka", "nginx", "linux", "api", "rest", "sql", "nosql", "maven", "gradle"}) {
                if (lower.contains(kw)) techHits++;
            }
            if (techHits >= 3) return true; // 包含3个以上技术栈词汇
        }
        log.info("关键词预检失败: hitCount={}, text前80字={}", hitCount, text.substring(0, Math.min(80, text.length())));
        return hitCount >= 2; // 降低门槛：至少2个
    }

    /**
     * V3: 兜底关键词匹配解析（含校验：无任何关键词命中则标记为非项目）
     */
    private KeywordMatchResult fallbackParseWithValidation(String text) {
        String lower = text.toLowerCase();
        List<PositionSuggestion> bestPositions = null;
        int bestScore = 0;

        for (KeywordRule rule : FALLBACK_RULES) {
            int score = 0;
            for (String kw : rule.keywords) {
                if (lower.contains(kw.toLowerCase())) score++;
            }
            if (score > bestScore) {
                bestScore = score;
                bestPositions = rule.positions;
            }
        }

        String summary;
        boolean isNotProject = false;

        if (bestPositions == null || bestPositions.isEmpty()) {
            // V3: 无任何规则命中 → 判定为非项目
            log.info("兜底解析: 无关键词命中, text前80字={}", text.substring(0, Math.min(80, text.length())));
            isNotProject = true;
            bestPositions = Collections.emptyList();
            summary = "";
        } else {
            summary = text.substring(0, Math.min(80, text.length()));
        }

        return new KeywordMatchResult(bestScore, new ParseResult(summary, bestPositions), isNotProject);
    }

    /** 兜底解析结果内部类 */
    private static class KeywordMatchResult {
        final int score;
        final ParseResult result;
        final boolean isNotProject;

        KeywordMatchResult(int score, ParseResult result, boolean isNotProject) {
            this.score = score;
            this.result = result;
            this.isNotProject = isNotProject;
        }
    }

    @SuppressWarnings("unchecked")
    private ParseResult buildFromMap(Map<String, Object> map) {
        String summary = (String) map.getOrDefault("projectSummary", "");
        List<Map<String, Object>> posList = (List<Map<String, Object>>) map.get("positions");
        List<PositionSuggestion> positions = new ArrayList<>();

        if (posList != null) {
            for (Map<String, Object> pm : posList) {
                PositionSuggestion pos = PositionSuggestion.builder()
                    .positionTitle((String) pm.get("positionTitle"))
                    .headcount(pm.get("headcount") instanceof Number ? ((Number) pm.get("headcount")).intValue() : 1)
                    .skillRequirements(new ArrayList<>())
                    .build();

                List<Map<String, Object>> skills = (List<Map<String, Object>>) pm.get("skillRequirements");
                if (skills != null) {
                    for (Map<String, Object> sm : skills) {
                        pos.getSkillRequirements().add(SkillRequirement.builder()
                            .skillName((String) sm.get("skillName"))
                            .requiredLevel((String) sm.get("requiredLevel"))
                            .build());
                    }
                }
                positions.add(pos);
            }
        }

        return new ParseResult(summary, positions);
    }

    // ---- 便捷构造方法 ----
    private static PositionSuggestion pos(String title, List<SkillRequirement> skills, int headcount) {
        return PositionSuggestion.builder()
            .positionTitle(title)
            .skillRequirements(skills)
            .headcount(headcount)
            .build();
    }

    private static List<SkillRequirement> skills(String... nameLevelPairs) {
        List<SkillRequirement> list = new ArrayList<>();
        for (int i = 0; i < nameLevelPairs.length; i += 2) {
            list.add(SkillRequirement.builder()
                .skillName(nameLevelPairs[i])
                .requiredLevel(nameLevelPairs[i + 1])
                .build());
        }
        return list;
    }

    private static List<SkillRequirement> java(String level, String... rest) {
        List<SkillRequirement> list = new ArrayList<>();
        list.add(SkillRequirement.builder().skillName("Java").requiredLevel(level).build());
        list.addAll(skills(rest));
        return list;
    }

    /** 兜底关键词规则 */
    private static class KeywordRule {
        final List<String> keywords;
        final List<PositionSuggestion> positions;
        KeywordRule(List<String> keywords, List<PositionSuggestion> positions) {
            this.keywords = keywords;
            this.positions = positions;
        }
    }
}
