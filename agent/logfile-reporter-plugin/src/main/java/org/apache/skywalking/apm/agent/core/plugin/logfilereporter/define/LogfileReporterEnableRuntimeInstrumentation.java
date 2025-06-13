package org.apache.skywalking.apm.agent.core.plugin.logfilereporter.define;

import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.named;
import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import java.util.Map;

import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.StaticMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.ClassStaticMethodsEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch;
import org.apache.skywalking.apm.agent.core.plugin.match.NameMatch;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.description.method.MethodDescription;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatcher;

/**
 * Refer To {@code TraceContextActivation}
 * <p>
 */
public class LogfileReporterEnableRuntimeInstrumentation extends ClassStaticMethodsEnhancePluginDefine {

	private static final String ENHANCE_CLASS = "org.apache.skywalking.apm.toolkit.SWLogfileReporterUtils";
	private static final String ENHANCE_METHOD = "enableReport";
	private static final String ENHANCE_METHOD_2 = "disableReport";
	private static final String ENHANCE_METHOD_3 = "statisticStatus";

	private static final String INTERCEPTOR_CLASS = "org.apache.skywalking.apm.agent.core.plugin.logfilereporter.LogfileReporterEnableRuntimeInterceptor";
	private static final String INTERCEPTOR_CLASS_2 = "org.apache.skywalking.apm.agent.core.plugin.logfilereporter.LogfileReporterDisableRuntimeInterceptor";
	private static final String INTERCEPTOR_CLASS_3 = "org.apache.skywalking.apm.agent.core.plugin.logfilereporter.LogfileReporterStatusExposeInterceptor";

	private static final ILog LOGGER = LogManager.getLogger(LogfileReporterEnableRuntimeInstrumentation.class);

	/**
	 * @return the target class, which needs active.
	 */
	@Override
	protected ClassMatch enhanceClass() {
		LOGGER.warn("### enhanceClass, {} ", ENHANCE_CLASS);
		NameMatch byName = NameMatch.byName(ENHANCE_CLASS);		
		return byName;
	}

	/**
	 * @return the collection of {@link StaticMethodsInterceptPoint}, represent the
	 *         intercepted methods and their interceptors.
	 */
	@Override
	public StaticMethodsInterceptPoint[] getStaticMethodsInterceptPoints() {
		return new StaticMethodsInterceptPoint[] { new StaticMethodsInterceptPoint() {
			@Override
			public ElementMatcher<MethodDescription> getMethodsMatcher() {
				LOGGER.warn("### getMethodsMatcher1 - {} ", named(ENHANCE_METHOD).and(takesArgument(0, Map.class)));
				return named(ENHANCE_METHOD);// .and(takesArgument(0, Map.class));
			}

			@Override
			public String getMethodsInterceptor() {
				return INTERCEPTOR_CLASS;
			}

			@Override
			public boolean isOverrideArgs() {
				return false;
			}
		}, new StaticMethodsInterceptPoint() {
			@Override
			public ElementMatcher<MethodDescription> getMethodsMatcher() {
				LOGGER.warn("### getMethodsMatcher2 - {} ", named(ENHANCE_METHOD_2).and(takesArgument(0, Map.class)));
				return named(ENHANCE_METHOD_2);// .and(takesArgument(0, Map.class));
			}

			@Override
			public String getMethodsInterceptor() {
				return INTERCEPTOR_CLASS_2;
			}

			@Override
			public boolean isOverrideArgs() {
				return false;
			}
		}, new StaticMethodsInterceptPoint() {
			@Override
			public ElementMatcher<MethodDescription> getMethodsMatcher() {
				LOGGER.warn("### getMethodsMatcher3 - {} ", named(ENHANCE_METHOD_3).and(takesNoArguments()));
				return named(ENHANCE_METHOD_3);// .and(takesNoArguments());
			}

			@Override
			public String getMethodsInterceptor() {
				return INTERCEPTOR_CLASS_3;
			}

			@Override
			public boolean isOverrideArgs() {
				return false;
			}
		} };
	}	
}
