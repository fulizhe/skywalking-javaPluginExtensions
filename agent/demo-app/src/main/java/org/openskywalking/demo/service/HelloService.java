/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openskywalking.demo.service;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.skywalking.apm.toolkit.trace.ActiveSpan;
import org.apache.skywalking.apm.toolkit.trace.RunnableWrapper;
import org.apache.skywalking.apm.toolkit.trace.Tag;
import org.apache.skywalking.apm.toolkit.trace.Tags;
import org.apache.skywalking.apm.toolkit.trace.Trace;
import org.apache.skywalking.apm.toolkit.trace.TraceCrossThread;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import org.openskywalking.demo.mapper.UserMapper;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.StopWatch;
import cn.hutool.core.lang.Console;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.RandomUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class HelloService {

	@Autowired
	private UserMapper userMapper;
	
	@Autowired
	private JdbcTemplate jdbcTemplate;
	

	public String sayHello() {
		return "hello world-- skywalking";
	}

	public String dbByMybatis() {
		userMapper.query("lq");
		return "query db By Mybatis";
	}
	
	public String queryDbByJdbc() {
		jdbcTemplate
				.queryForList("SELECT * FROM \"USER\" WHERE use_name = ?", RandomUtil.randomString(5));
		return "query db By JDBC";
	}	

	public String throwException() {
		throw new RuntimeException("抛出一异常");
	}

	@Async
	public void asyncSayHello() {
		Console.log("CURRENT THREAD NAME : 【 {} 】", Thread.currentThread().getName());
	}
	
	public void asyncSayHello2() {		
		new Thread(RunnableWrapper.of(() -> Console.log("子线程信息, CURRENT THREAD NAME : 【 {} 】", Thread.currentThread().getName()))).start();
	}
	
	public void asyncSayHello3() {		
		new Thread(new InnerThreadTask()).start();
	}	

	@Trace
	@Tags({ @Tag(key = "从方法参数中获取值", value = "arg[0]"), //
			@Tag(key = "从返回值中获取值", value = "returnedObj.name") //
	})
	public Map<String,Object> traceServiceMethodWithAnnotation(String param) {		
        log.info("如果此方法没有被SkyWalking收集，但是又需要被收集到，可以加上@Trace注解");
        
        return Collections.singletonMap("name","LQ");
	}
	
	public void traceServiceMethod2WithApi() {		
		ActiveSpan.tag("new-tag", "newTag");
        ActiveSpan.info("输出信息---LQ");        
	}	
	

	public void debug(int i) {
		long randomLong = RandomUtil.randomLong(2000);
		ActiveSpan.debug("DEBUG ---- " + i + "------ " + randomLong);
		ThreadUtil.sleep(randomLong);		
		profile2(i-1);
	}	
	
	
	
	public void profile(int i){
		// https://mp.weixin.qq.com/s/z5oIiVTkQgcX5NhHHQAvTg 【Apache SkyWalking 7 在线代码级性能剖析，补全分布式追踪的最后一块“短板”】
		final CountDownLatch countDownLatch= new CountDownLatch(2);

		ThreadUtil.execute(new Task1(countDownLatch));
		ThreadUtil.execute(new Task2(countDownLatch));
		try {
			Console.log(countDownLatch.await(500, TimeUnit.MILLISECONDS));
		} catch (InterruptedException e) {
			log.error(e.getMessage(),e);
		}
		
	}
//		long randomLong = RandomUtil.randomLong(2000);
//		ActiveSpan.debug("profile ---- " + i + "------ " + randomLong);
//		ThreadUtil.sleep(randomLong);		
//		profile2(i-1);
//	}	
//	
	private String profile2(int i){
		long randomLong = RandomUtil.randomLong(2000);
		ActiveSpan.debug("profile ---- " + i + "------ " + randomLong);
		ThreadUtil.sleep(randomLong);	
		profile3(i-1);
		return "profile2";
	}
	
	private String profile3(int i){
		long randomLong = RandomUtil.randomLong(2000);
		ActiveSpan.debug("profile ---- " + i + "------ " + randomLong);
		ThreadUtil.sleep(randomLong);	
		return "profile3";
	}	
	
	public void log(){
		log.info("日志上报------1");
		ThreadUtil.sleep(RandomUtil.randomLong(100,500));
		log.info("日志上报------2");
	}
	
	public void logError(){
		log.info("日志上报------3");
		ThreadUtil.sleep(RandomUtil.randomLong(100,500));
		log.error("发生错误", new RuntimeException("Error Occur"));
	}	
	
	public void tracePrivateMethod(){
		ActiveSpan.debug("call private method ---- " + privateMethod(RandomUtil.randomInt(10)));
	}
	
	@Trace
	@Tags({ @Tag(key = "从方法参数中获取值(private)", value = "arg[0]"), //
			@Tag(key = "从返回值中获取值(private)", value = "returnedObj.name") //
	})	
	private String privateMethod(int i){
		return Convert.toStr(i);
	}
	
	// ==================================
	@TraceCrossThread
	private static class InnerThreadTask implements Runnable{

		@Override
		public void run() {
			Console.log("子线程信息, CURRENT THREAD NAME : 【 {} 】", Thread.currentThread().getName());
		}
		
	}

}
