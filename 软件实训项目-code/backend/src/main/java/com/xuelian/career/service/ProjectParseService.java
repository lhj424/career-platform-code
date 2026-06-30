package com.xuelian.career.service;

import com.xuelian.career.dto.response.EnterpriseRecommendResponse;

import java.util.List;

/**
 * 项目需求解析服务 - 调用 DeepSeek 分析项目描述，输出岗位建议和技能要求
 */
public interface ProjectParseService {

    /**
     * 解析项目描述，返回岗位建议
     * @param projectDescription 项目描述文本
     * @return 解析结果（projectSummary + positions），解析失败返回 null
     */
    ParseResult parseProject(String projectDescription);

    /**
     * 解析结果
     */
    class ParseResult {
        private String projectSummary;
        private List<EnterpriseRecommendResponse.PositionSuggestion> positions;
        /** V3: 解析是否通过项目有效性校验 */
        private boolean valid = true;
        /** V3: 校验失败时的错误信息 */
        private String errorMessage;

        public ParseResult() {}

        public ParseResult(String projectSummary, List<EnterpriseRecommendResponse.PositionSuggestion> positions) {
            this.projectSummary = projectSummary;
            this.positions = positions;
        }

        /** V3: 创建校验失败的解析结果 */
        public static ParseResult invalid(String errorMessage) {
            ParseResult r = new ParseResult();
            r.valid = false;
            r.errorMessage = errorMessage;
            r.positions = java.util.Collections.emptyList();
            return r;
        }

        public String getProjectSummary() { return projectSummary; }
        public void setProjectSummary(String projectSummary) { this.projectSummary = projectSummary; }
        public List<EnterpriseRecommendResponse.PositionSuggestion> getPositions() { return positions; }
        public void setPositions(List<EnterpriseRecommendResponse.PositionSuggestion> positions) { this.positions = positions; }
        public boolean isValid() { return valid; }
        public void setValid(boolean valid) { this.valid = valid; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }
}
