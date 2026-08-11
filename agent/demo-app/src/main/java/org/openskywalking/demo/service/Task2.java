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

import java.util.concurrent.CountDownLatch;

import cn.hutool.core.lang.Console;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.RandomUtil;

public class Task2 implements Runnable {

	final CountDownLatch countDownLatch;
	
	public Task2(CountDownLatch countDownLatch) {
		super();
		this.countDownLatch = countDownLatch;
	}


	@Override
	public void run() {
		long randomLong = RandomUtil.randomLong(600);
		ThreadUtil.sleep(randomLong);
		Console.log("task2 --- {}", randomLong);					
		countDownLatch.countDown();	
	}
}
