package com.xuelian.career.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

/**
 * 管理后台数据看板响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardResponse {
    /** 用户总数 */
    private Long totalUsers;
    /** 正常用户数 */
    private Long activeUsers;
    /** 已禁用用户数 */
    private Long disabledUsers;
    /** 学生数量 */
    private Long studentCount;
    /** 企业HR数量 */
    private Long hrCount;
    /** 管理员数量 */
    private Long adminCount;
    /** 岗位总数 */
    private Long totalJobs;

    private Long totalAssessments;
    private Long totalMatches;
    private Long totalResumeAnalysis;
    private Long totalInterviews;
    private Long totalEnterpriseRecommendations;
    private List<Map<String, Object>> weeklyTrend;
}
