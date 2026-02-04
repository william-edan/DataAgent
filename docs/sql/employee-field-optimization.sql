-- ============================================
-- 员工档案字段优化配置
-- 用途：配置字段显示优先级、隐藏规则和时间格式化
-- ============================================

-- ============================================
-- 员工表 (oa_admin) 字段配置
-- ============================================

-- 核心字段（优先级 1-10）
ALTER TABLE oa_admin MODIFY COLUMN name VARCHAR(50) COMMENT '员工姓名|priority:1';
ALTER TABLE oa_admin MODIFY COLUMN mobile VARCHAR(20) COMMENT '手机号码|priority:2';
ALTER TABLE oa_admin MODIFY COLUMN sex TINYINT COMMENT '员工性别：0女,1男|priority:3';
ALTER TABLE oa_admin MODIFY COLUMN age INT COMMENT '年龄|priority:4';
ALTER TABLE oa_admin MODIFY COLUMN email VARCHAR(100) COMMENT '电子邮箱|priority:5';
ALTER TABLE oa_admin MODIFY COLUMN type TINYINT COMMENT '员工类型：0未设置,1正式员工,2试用员工,3实习生|priority:6';
ALTER TABLE oa_admin MODIFY COLUMN is_staff TINYINT COMMENT '身份类型：1企业员工,2劳务派遣,3兼职员工|priority:7';
ALTER TABLE oa_admin MODIFY COLUMN status TINYINT COMMENT '状态：-1待入职,0禁止登录,1正常,2离职|priority:8';
ALTER TABLE oa_admin MODIFY COLUMN entry_time INT COMMENT '入职日期|priority:9|formatTimestamp:true';

-- 次要字段（优先级 11-20）
ALTER TABLE oa_admin MODIFY COLUMN idcard VARCHAR(18) COMMENT '身份证|priority:15';

-- 技术字段（自动隐藏，无需配置）
-- id, did, position_id, position_rank, created_at, updated_at

-- 如果需要显示某些技术字段，可以强制显示：
-- ALTER TABLE oa_admin MODIFY COLUMN id INT COMMENT '员工ID|hidden:false|priority:99';


-- ============================================
-- 员工档案表 (oa_admin_profiles) 字段配置
-- ============================================

-- 核心字段
ALTER TABLE oa_admin_profiles MODIFY COLUMN types TINYINT COMMENT '类型：1教育经历,2工作经历,3相关证书,4计算机技能,5语言能力|priority:1';
ALTER TABLE oa_admin_profiles MODIFY COLUMN title VARCHAR(200) COMMENT '院校/培训机构/公司名称/证书名称/技能名称/语言名称|priority:2';
ALTER TABLE oa_admin_profiles MODIFY COLUMN major VARCHAR(100) COMMENT '所学专业|priority:3';
ALTER TABLE oa_admin_profiles MODIFY COLUMN degree VARCHAR(50) COMMENT '所获学历|priority:4';
ALTER TABLE oa_admin_profiles MODIFY COLUMN position VARCHAR(100) COMMENT '职位|priority:5';
ALTER TABLE oa_admin_profiles MODIFY COLUMN organization VARCHAR(200) COMMENT '颁发机构|priority:6';
ALTER TABLE oa_admin_profiles MODIFY COLUMN start_time DATE COMMENT '开始时间|priority:7';
ALTER TABLE oa_admin_profiles MODIFY COLUMN end_time DATE COMMENT '结束时间|priority:8';
ALTER TABLE oa_admin_profiles MODIFY COLUMN know TINYINT COMMENT '熟悉程度：0一般,1熟练,2精通|priority:9';
ALTER TABLE oa_admin_profiles MODIFY COLUMN content TEXT COMMENT '备注说明|priority:10';

-- 技术字段（自动隐藏）
-- id, profile_id, created_at, updated_at


-- ============================================
-- 验证配置
-- ============================================

-- 查看oa_admin表字段配置
SELECT
    COLUMN_NAME AS '字段名',
    COLUMN_TYPE AS '类型',
    COLUMN_COMMENT AS '注释'
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'oa_admin'
ORDER BY ORDINAL_POSITION;

-- 查看oa_admin_profiles表字段配置
SELECT
    COLUMN_NAME AS '字段名',
    COLUMN_TYPE AS '类型',
    COLUMN_COMMENT AS '注释'
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'oa_admin_profiles'
ORDER BY ORDINAL_POSITION;


-- ============================================
-- 测试查询
-- ============================================

-- 测试员工档案查询（应该只显示配置的核心字段）
-- SELECT * FROM oa_admin WHERE name = '员工';

-- 预期显示字段（按优先级排序）：
-- 1. 员工姓名
-- 2. 手机号码
-- 3. 员工性别（显示"男"或"女"）
-- 4. 年龄
-- 5. 电子邮箱
-- 6. 员工类型（显示"正式员工"等）
-- 7. 身份类型（显示"企业员工"等）
-- 8. 状态（显示"正常"等）
-- 9. 入职日期（显示"2026-01-19 00:00:00"格式）

-- 隐藏字段：
-- id, did, position_id, position_rank, created_at, updated_at 等
