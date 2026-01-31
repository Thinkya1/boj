-- 为题目表添加难度字段（1-简单 2-中等 3-困难）
ALTER TABLE question
    ADD COLUMN difficulty tinyint default 1 not null comment '难度（1-简单 2-中等 3-困难）' AFTER questionNumber;

-- 历史数据默认设置为 1
UPDATE question
SET difficulty = 1
WHERE difficulty IS NULL;
