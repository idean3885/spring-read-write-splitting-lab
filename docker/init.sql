-- 소스에서만 실행한다. 레플리카는 복제로 같은 상태가 된다
-- 두 구성(single · split)이 스키마를 나눠 쓴다. 서버 쪽 문장 통계를 스키마로 구분하기 위해서다
CREATE DATABASE sample_single;
CREATE DATABASE sample_split;

CREATE TABLE sample_single.usage_sample (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  collected_at DATETIME(3) NOT NULL,
  value_mb INT NOT NULL,
  KEY idx_collected_at (collected_at)
);
CREATE TABLE sample_split.usage_sample LIKE sample_single.usage_sample;

-- 조회가 무게를 갖도록 미리 채운다. 집계 조회는 최근 N건을 훑으므로 N 의 상한(200,000)만큼 있으면 된다
SET SESSION cte_max_recursion_depth = 200000;
INSERT INTO sample_single.usage_sample (collected_at, value_mb)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 200000)
SELECT NOW(3) - INTERVAL (200000 - n) SECOND, 100 + (n * 37) % 3900 FROM seq;
INSERT INTO sample_split.usage_sample (collected_at, value_mb)
SELECT collected_at, value_mb FROM sample_single.usage_sample ORDER BY id;

-- 앱 계정. SUPER 가 없으므로 레플리카(read_only)에는 쓸 수 없다
CREATE USER 'lab'@'%' IDENTIFIED BY 'lab';
GRANT SELECT, INSERT ON sample_single.* TO 'lab'@'%';
GRANT SELECT, INSERT ON sample_split.* TO 'lab'@'%';

CREATE USER 'repl'@'%' IDENTIFIED BY 'repl';
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
