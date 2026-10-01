-- 소스에서만 실행한다. 레플리카는 복제로 같은 상태가 된다
-- 두 구성(broken · fixed)이 스키마를 나눠 쓴다. 서버 쪽 문장 통계를 스키마로 구분하기 위해서다
CREATE DATABASE sample_broken;
CREATE DATABASE sample_fixed;

CREATE TABLE sample_broken.usage_sample (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  collected_at DATETIME(3) NOT NULL,
  value_mb INT NOT NULL,
  KEY idx_collected_at (collected_at)
);
CREATE TABLE sample_fixed.usage_sample LIKE sample_broken.usage_sample;

-- 앱 계정. SUPER 가 없으므로 레플리카(read_only)에는 쓸 수 없다
CREATE USER 'lab'@'%' IDENTIFIED BY 'lab';
GRANT SELECT, INSERT ON sample_broken.* TO 'lab'@'%';
GRANT SELECT, INSERT ON sample_fixed.* TO 'lab'@'%';

CREATE USER 'repl'@'%' IDENTIFIED BY 'repl';
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
