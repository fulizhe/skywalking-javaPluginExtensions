-- DROP DATABASE IF EXISTS sampledb;
-- CREATE DATABASE sampledb DEFAULT CHARACTER SET utf8;
-- USE sampledb;

-- 创建用户表(user 是 H2 2.x 保留字,加引号)
create table if not exists "USER" (
USE_ID int not null primary key auto_increment,
USE_NAME varchar(100),
USE_SEX varchar(1),
USE_AGE NUMBER(3),
USE_ID_NO VARCHAR(18),
USE_PHONE_NUM VARCHAR(11),
USE_EMAIL VARCHAR(100),
CREATE_TIME DATE,
MODIFY_TIME DATE,
USE_STATE VARCHAR(1));

-- 创建CAT日志表
create table if not exists cat_log_info (
TRACEID varchar(60) not null primary key comment '主键',
TYPEE varchar(20) not null comment '类别',
ELAPSE int not null comment '耗时',
DATEE TIMESTAMP not null comment '发生时间', 
URL varchar(200) not null comment 'URL',
PARAM varchar(500)  null comment '入参',
RESULT varchar(500) null comment '出参',
LOGINFO TEXT not null comment '全链路日志信息');