package org.apache.skywalking.apm.agent.core.plugin.localprofile.define;

import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.named;

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
public class LocalProfileRuntimeInstrumentation extends ClassStaticMethodsEnhancePluginDefine {

	private static final String ENHANCE_CLASS = "org.apache.skywalking.apm.toolkit.SWLogfileReporterUtils";
	private static final String ENHANCE_METHOD = "startProfile";
	private static final String ENHANCE_METHOD_2 = "getProfileDatas";

	private static final String INTERCEPTOR_CLASS = "org.apache.skywalking.apm.agent.core.plugin.localprofile.LocalPorfileCallInterceptor";
	private static final String INTERCEPTOR_CLASS_2 = "org.apache.skywalking.apm.agent.core.plugin.localprofile.LocalProfileStatusExposeInterceptor";

	private static final ILog LOGGER = LogManager.getLogger(LocalProfileRuntimeInstrumentation.class);

	/**
	 * @return the target class, which needs active.
	 */
	@Override
	protected ClassMatch enhanceClass() {
		LOGGER.warn("### enhanceClass, {} ", ENHANCE_CLASS);
		return NameMatch.byName(ENHANCE_CLASS);
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
		}};
	}	
}
