package org.apache.skywalking.apm.agent.core.plugin.logfilereporter.define;

import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.named;
import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.StaticMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.ClassStaticMethodsEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch;
import org.apache.skywalking.apm.agent.core.plugin.match.NameMatch;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.description.method.MethodDescription;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatcher;

/**
 * 为 {@code SWLogfileReporterUtils.hotLayerStat()} 提供增强。
 *
 * <p>与 {@link LogfileReporterEnableRuntimeInstrumentation} 同目标类，但<b>独立成
 * {@code PluginDefine}</b>：那是既有代码，往它的拦截点数组里追加一项会改动那段代码，
 * 而冻结线的契约是"不动现有代码"。独立之后既有文件一行未改，注销这一个 define 即可
 * 完全回退该能力。</p>
 *
 * <p>目标方法 {@code hotLayerStat()} 需要<b>应用侧</b>提供同名宿主工具类桩
 * （{@code org.apache.skywalking.apm.toolkit.SWLogfileReporterUtils}）。桩类不在本插件内 ——
 * {@code NameMatch.byName} 匹配不到就静默不生效，故未加桩的应用不受影响。</p>
 */
public class HotLayerStatInstrumentation extends ClassStaticMethodsEnhancePluginDefine {

	private static final String ENHANCE_CLASS = "org.apache.skywalking.apm.toolkit.SWLogfileReporterUtils";
	private static final String ENHANCE_METHOD = "hotLayerStat";

	private static final String INTERCEPTOR_CLASS =
			"org.apache.skywalking.apm.agent.core.plugin.logfilereporter.HotLayerStatExposeInterceptor";

	private static final ILog LOGGER = LogManager.getLogger(HotLayerStatInstrumentation.class);

	@Override
	protected ClassMatch enhanceClass() {
		LOGGER.warn("### enhanceClass, {} ", ENHANCE_CLASS);
		return NameMatch.byName(ENHANCE_CLASS);
	}

	@Override
	public StaticMethodsInterceptPoint[] getStaticMethodsInterceptPoints() {
		return new StaticMethodsInterceptPoint[] { new StaticMethodsInterceptPoint() {
			@Override
			public ElementMatcher<MethodDescription> getMethodsMatcher() {
				LOGGER.warn("### getMethodsMatcher - {} ", named(ENHANCE_METHOD).and(takesNoArguments()));
				return named(ENHANCE_METHOD);
			}

			@Override
			public String getMethodsInterceptor() {
				return INTERCEPTOR_CLASS;
			}

			@Override
			public boolean isOverrideArgs() {
				return false;
			}
		} };
	}
}