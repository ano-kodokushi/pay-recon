package org.apache.maven.surefire.junitplatform;

import org.apache.maven.surefire.api.provider.AbstractProvider;
import org.apache.maven.surefire.api.provider.ProviderParameters;
import org.apache.maven.surefire.api.report.LegacyPojoStackTraceWriter;
import org.apache.maven.surefire.api.report.ReporterFactory;
import org.apache.maven.surefire.api.report.RunListener;
import org.apache.maven.surefire.api.report.RunMode;
import org.apache.maven.surefire.api.report.SimpleReportEntry;
import org.apache.maven.surefire.api.suite.RunResult;
import org.apache.maven.surefire.api.testset.TestSetFailedException;
import org.apache.maven.surefire.api.util.ScanResult;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 离线 surefire JUnit Platform provider。
 *
 * <p><b>为什么需要它</b>：开发这一工程的机器没有外网，官方
 * {@code org.apache.maven.surefire:surefire-junit-platform:<surefire版本>} 不在本地 Maven 仓，
 * {@code mvn test} 会直接报
 * {@code The following artifacts could not be resolved: ...surefire-junit-platform...}。
 * 而它所需的**全部** SPI（surefire-api / surefire-booter / maven-surefire-common）
 * 与 JUnit Platform 组件（launcher / engine / commons）**都已在本地仓**，缺的只是胶水层。
 * 因此这里实现一个行为等价的 provider，坐标与官方一致，pom 不需要任何特殊配置。
 *
 * <p><b>实现要点</b>（都是踩过的坑）：
 * <ul>
 *   <li>{@code SimpleReportEntry} 的 {@code testRunId} **不能为 null**：
 *       父进程的 {@code EventDecoder.toReportEntry} 会对它调 {@code longValue()}，
 *       传 null 会在父进程抛 NPE 并让所有事件解析失败（表现为 Tests run: 0）。</li>
 *   <li>{@code systemProperties} **不能为 null**：{@code SimpleReportEntry} 内部直接
 *       {@code entrySet()}，传 null 会在子进程抛 NPE。</li>
 *   <li>必须**逐用例**发送 starting/succeeded/failed/skipped 事件，
 *       只汇报汇总的话父进程统计不到任何用例（同样表现为 Tests run: 0）。</li>
 * </ul>
 */
public class JUnitPlatformProvider extends AbstractProvider {

    private static final String SOURCE_NAME = "JUnit Platform";
    private static final String SOURCE_TEXT = "JUnit Platform";
    private static final RunMode RUN_MODE = RunMode.NORMAL_RUN;

    private final ProviderParameters parameters;
    private final ScanResult scanResult;
    private final AtomicLong testRunId = new AtomicLong(1);
    private volatile boolean cancelled;

    /** surefire 通过这个构造函数反射实例化 provider。 */
    public JUnitPlatformProvider(ProviderParameters parameters) {
        this.parameters = parameters;
        this.scanResult = parameters.getScanResult();
    }

    @Override
    public Iterable<Class<?>> getSuites() {
        Set<Class<?>> suites = new HashSet<>();
        if (scanResult == null) {
            return suites;
        }
        ClassLoader classLoader = parameters.getTestClassLoader();
        for (int i = 0; i < scanResult.size(); i++) {
            try {
                suites.add(Class.forName(scanResult.getClassName(i), false, classLoader));
            } catch (Throwable ignored) {
                // 加载不了的候选类交给 JUnit Platform 自己报告
            }
        }
        return suites;
    }

    @Override
    public RunResult invoke(Object forkTestSet) throws TestSetFailedException {
        ReporterFactory reporterFactory = parameters.getReporterFactory();
        RunListener listener = reporterFactory.createTestReportListener();

        List<DiscoverySelector> selectors = new ArrayList<>();
        int scanned = scanResult == null ? 0 : scanResult.size();
        for (int i = 0; i < scanned; i++) {
            selectors.add(DiscoverySelectors.selectClass(scanResult.getClassName(i)));
        }

        if (selectors.isEmpty()) {
            listener.testExecutionSkippedByUser();
            return reporterFactory.close();
        }

        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectors)
                .build();

        Launcher launcher = LauncherFactory.create();
        TestPlan testPlan = launcher.discover(request);

        listener.testSetStarting(new SimpleReportEntry(
                RUN_MODE, testRunId.get(), SOURCE_NAME, SOURCE_TEXT, null, SOURCE_TEXT,
                Collections.emptyMap()));

        launcher.execute(testPlan, new SurefireBridge(listener));

        listener.testSetCompleted(new SimpleReportEntry(
                RUN_MODE, testRunId.get(), SOURCE_NAME, SOURCE_TEXT, null, SOURCE_TEXT,
                Collections.emptyMap()));

        // 逐用例事件已由 SurefireBridge 发出，surefire 据此统计用例数与失败数
        return reporterFactory.close();
    }

    @Override
    public void cancel() {
        this.cancelled = true;
    }

    /** 供诊断使用。 */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * 把 JUnit Platform 的执行事件翻译成 surefire 的 reporter 事件。
     *
     * <p>只处理"测试用例"（{@code isTest()}）级别的事件，容器（类/引擎）不单独上报，
     * 与 surefire 的统计口径一致。
     */
    private final class SurefireBridge implements TestExecutionListener {

        private final RunListener listener;

        SurefireBridge(RunListener listener) {
            this.listener = listener;
        }

        @Override
        public void executionStarted(TestIdentifier id) {
            if (id.isTest()) {
                listener.testStarting(entry(id));
            }
        }

        @Override
        public void executionSkipped(TestIdentifier id, String reason) {
            if (id.isTest()) {
                listener.testSkipped(skipped(id, reason == null ? "skipped" : reason));
            }
        }

        @Override
        public void executionFinished(TestIdentifier id, org.junit.platform.engine.TestExecutionResult result) {
            if (!id.isTest()) {
                return;
            }
            Throwable throwable = result.getThrowable().orElse(null);
            switch (result.getStatus()) {
                case SUCCESSFUL -> listener.testSucceeded(entry(id));
                case ABORTED -> listener.testSkipped(skipped(id,
                        throwable == null ? "aborted" : String.valueOf(throwable.getMessage())));
                case FAILED -> {
                    if (throwable instanceof AssertionError) {
                        listener.testFailed(entryWithThrowable(id, throwable));
                    } else {
                        listener.testError(entryWithThrowable(id, throwable));
                    }
                }
                default -> listener.testSucceeded(entry(id));
            }
        }

        private SimpleReportEntry entry(TestIdentifier id) {
            return new SimpleReportEntry(
                    RUN_MODE, testRunId.get(), classNameOf(id), classNameOf(id),
                    id.getDisplayName(), id.getDisplayName(), Collections.emptyMap());
        }

        private SimpleReportEntry entryWithThrowable(TestIdentifier id, Throwable t) {
            return new SimpleReportEntry(
                    RUN_MODE, testRunId.get(), classNameOf(id), classNameOf(id),
                    id.getDisplayName(), id.getDisplayName(),
                    new LegacyPojoStackTraceWriter(classNameOf(id), id.getDisplayName(), t),
                    null,
                    String.valueOf(t.getMessage()),
                    Collections.emptyMap());
        }

        /** 跳过/中止的用例：显式传 null 给 stackTraceWriter 槽位以选定正确的重载。 */
        private SimpleReportEntry skipped(TestIdentifier id, String message) {
            return new SimpleReportEntry(
                    RUN_MODE, testRunId.get(), classNameOf(id), classNameOf(id),
                    id.getDisplayName(), id.getDisplayName(),
                    (org.apache.maven.surefire.api.report.StackTraceWriter) null,
                    null,
                    message,
                    Collections.emptyMap());
        }
    }

    // ------------------------------------------------------------------
    // 从 JUnit 的 TestIdentifier 提取 surefire 需要的名称
    // ------------------------------------------------------------------

    /** 用例所属测试类的全名；从 uniqueId 里的 [class:...] 段解析。 */
    private static String classNameOf(TestIdentifier id) {
        String uniqueId = id.getUniqueId();
        int idx = uniqueId.indexOf("[class:");
        if (idx >= 0) {
            String rest = uniqueId.substring(idx + "[class:".length());
            int end = rest.indexOf(']');
            if (end > 0) {
                return rest.substring(0, end);
            }
        }
        return SOURCE_NAME;
    }
}
