package org.apache.skywalking.apm.plugin.hutool.v5.http;

import static org.junit.Assert.*;

import org.apache.skywalking.apm.plugin.jdbc.define.Constants;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import cn.hutool.core.lang.Console;

public class SqlitePlugin_Tests {

	@Before
	public void setUp() throws Exception {
	}

	@After
	public void tearDown() throws Exception {
	}

	@Test
	public void test() {
		Console.log(Constants.PREPARE_CALL_INTERCEPT_CLASS);
		
		Console.log(Constants.PREPARE_STATEMENT_METHOD_NAME);

		
		Console.log(Constants.PREPARE_STATEMENT_INTERCEPT_CLASS);

		
		Console.log(Constants.PREPARE_CALL_METHOD_NAME);


		Console.log(Constants.CREATE_STATEMENT_METHOD_NAME);

		Console.log(Constants.CREATE_STATEMENT_INTERCEPT_CLASS);

		Console.log(Constants.ROLLBACK_METHOD_NAME);
		Console.log(Constants.CLOSE_METHOD_NAME);
		Console.log(Constants.RELEASE_SAVE_POINT_METHOD_NAME);
		Console.log(Constants.COMMIT_METHOD_NAME);
		
		Console.log(Constants.SERVICE_METHOD_INTERCEPT_CLASS);
		
	}

}
