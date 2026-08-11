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

package org.openskywalking.demo.controller;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.skywalking.apm.toolkit.trace.ActiveSpan;
import org.apache.skywalking.apm.toolkit.trace.TraceContext;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import org.openskywalking.demo.config.WebUtil;
import org.openskywalking.demo.service.HelloService;
import org.openskywalking.demo.util.ContextCarrier;
import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;

import cn.hutool.core.collection.IterUtil;
import cn.hutool.core.lang.Console;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import io.swagger.annotations.ApiOperation;

@RestController
public class HelloController {

	@Autowired
	private HelloService helloService;

	@ApiOperation(value = "hello world", notes = "hello world")
	@ApiOperationSupport(order = 1)
	@GetMapping("/hello")
	public String hello(HttpServletRequest request) {
		String randomString = RandomUtil.randomString(5);
		LoggerFactory.getLogger(this.getClass()).info("接受到参数 {}", "name = " + randomString);


		IterUtil.asIterator(WebUtil.getHeaderNames()).forEachRemaining(
				headerName -> Console.log("### {} : {}", headerName, WebUtil.getRequest().getHeader(headerName)));

		if(StrUtil.isNotEmpty(WebUtil.getHeader("sw8"))){
			ContextCarrier deserialize = ContextCarrier.deserialize(WebUtil.getHeader("sw8"));
			Console.log("### PASSED TRACE_ID IS: [ {} ]", deserialize.getTraceId());
			Console.log("### TraceContext.traceId() IS: [ {} ]", TraceContext.traceId());
			Console.log("### TraceContext.segmentId() IS: [ {} ]", TraceContext.segmentId());
			Console.log("### TraceContext.spanId() IS: [ {} ]", TraceContext.spanId());				
		}	
		
		return helloService.sayHello();
	}
	
	@ApiOperation(value = "hello world Block", notes = "hello world Block")
	@ApiOperationSupport(order = 1)
	@GetMapping("/helloBlock")
	public String helloBlock(HttpServletRequest request) {
		int sleep = RandomUtil.randomInt(100, 2000);
		Console.error("### sleep {} ms", sleep);
		ThreadUtil.safeSleep(sleep);
		
		return helloService.sayHello();
	}	

	@ApiOperation(value = "日志上报", notes = "日志上报")
	@ApiOperationSupport(order = 2)
	@GetMapping("/log")
	public String log(HttpServletRequest request) {
		helloService.log();
		return "log";
	}

	@ApiOperation(value = "日志(异常)上报", notes = "日志(异常)上报")
	@ApiOperationSupport(order = 2)
	@GetMapping("/logError")
	public String logError(HttpServletRequest request) {
		helloService.logError();
		throw new RuntimeException("logError");
	}

	@ApiOperation(value = "@Async异步执行", notes = "异步执行, 使用Spring的注解@Async")
	@ApiOperationSupport(order = 3)
	@GetMapping("/helloAsync")
	public String helloAsync(HttpServletRequest request) {
		helloService.asyncSayHello();

		return "helloAsync";
	}

	@ApiOperation(value = "RunnableWrapper追踪异步操作", notes = "还有CallableWrapper和SupplierWrapper")
	@ApiOperationSupport(order = 4)
	@GetMapping("/helloAsync2")
	public String helloAsync2(HttpServletRequest request) {
		helloService.asyncSayHello2();

		return "helloAsync2";
	}

	@ApiOperation(value = "@TraceCrossThread追踪异步操作", notes = "只能注解在类级别, 不能在方法上")
	@ApiOperationSupport(order = 5)
	@GetMapping("/helloAsync3")
	public String helloAsync3(HttpServletRequest request) {
		helloService.asyncSayHello3();

		return "helloAsync2";
	}

	@ApiOperation(value = "查询数据库-Mybatis", notes = "查询数据库-Mybatis")
	@ApiOperationSupport(order = 6)
	@GetMapping("/queryDbByMybatis")
	public String queryDbByMybatis(HttpServletRequest request) {
		String dbByMybatis = helloService.dbByMybatis();

		return dbByMybatis;
	}

	@ApiOperation(value = "查询数据库-JDBC", notes = "查询数据库-JDBC")
	@ApiOperationSupport(order = 7)
	@GetMapping("/queryDbByJdbc")
	public String queryDbByJdbc(HttpServletRequest request) {
		String queryDbByJdbc = helloService.queryDbByJdbc();

		return queryDbByJdbc;
	}

	@ApiOperation(value = "测试异常", notes = "发生异常时候的表现")
	@ApiOperationSupport(order = 8)
	@GetMapping("/helloException")
	public String helloFail(HttpServletRequest request) {
		String randomString = RandomUtil.randomString(5);
		LoggerFactory.getLogger(this.getClass()).info("接受到参数 {}", "name = " + randomString);

		return helloService.throwException();
	}

	@ApiOperation(value = "使用注解@Trace追踪", notes = "使用注解@Trace进行追踪")
	@ApiOperationSupport(order = 9)
	@GetMapping("/traceServiceMethodWithAnnotation")
	public String traceServiceMethodWithAnnotation(HttpServletRequest request) {
		helloService.traceServiceMethodWithAnnotation("lq");

		return "traceServiceMethodWithAnnotation";
	}

	@ApiOperation(value = "使用API追踪", notes = "使用ActiveSpan类提供的API进行追踪")
	@ApiOperationSupport(order = 10)
	@GetMapping("/traceServiceMethod2WithApi")
	public String traceServiceMethod2WithApi(HttpServletRequest request) {
		ActiveSpan.info("输出信息---LQ--In Controller layer");
		ActiveSpan.tag("new-tag-controller", "newTag-controller");
		helloService.traceServiceMethod2WithApi();

		return "trace Service Method With Api";
	}

	@ApiOperation(value = "trace私有方法", notes = "私有方法使用@Trace也可以追踪到")
	@ApiOperationSupport(order = 11)
	@GetMapping("/traceInvokePrivateMethod")
	public String traceInvokePrivateMethod(HttpServletRequest request) {
		helloService.tracePrivateMethod();

		return "traceInvokePrivateMethod";
	}

	@ApiOperation(value = "性能剖析", notes = "利用方法栈快照，并对方法执行情况进行分析和汇总。并结合有限的分布式追踪 span 上下文，对代码执行速度进行估算")
	@ApiOperationSupport(order = 12)
	@GetMapping("/profile")
	public String profile(HttpServletRequest request) {
		helloService.profile(5);
		return "profile";
	}

	@ApiOperation(value = "DEBUG", notes = "")
	@ApiOperationSupport(order = 13)
	@GetMapping("/debug")
	public String debug(HttpServletRequest request) {
		helloService.debug(5);
		return "DEBUG";
	}

	@RequestMapping(value = "/tile/1.0.0/{ServiceName}/{layerId}/{Style}/{TileMatrixSet}/{level}/{TileRow}/{fileName}", method = RequestMethod.GET)
	public String PathVariable(@PathVariable String ServiceName, @PathVariable String Style, @PathVariable int layerId,
			@PathVariable String TileMatrixSet, @PathVariable int level, @PathVariable int TileRow,
			@PathVariable String fileName, HttpServletRequest request, HttpServletResponse response) {

		return "@PathVariable";

	}
}