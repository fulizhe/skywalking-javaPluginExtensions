package org.openskywalking.demo.load;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link HttpLoadTest#splitPaths(String)} 的边界用例。
 *
 * <p>为什么要有：路径集用逗号分隔，而逗号在 query 值里合法 —— 朴素 {@code split(",")}
 * 会把一条路径静默拆成两条（症状是"少打一半路径"或"凭空多出 400"，不报错）。
 * 这里把每种切/不切的判据钉死，避免以后有人"顺手简化"回 {@code split(",")}。
 */
class SplitPathsTest {

    @Test
    void 逗号后跟斜杠才切() {
        assertArrayEquals(new String[]{"/hello", "/fullSample"}, HttpLoadTest.splitPaths("/hello,/fullSample"));
        assertArrayEquals(new String[]{"/hello", "/longTimeTask"},
            HttpLoadTest.splitPaths("/hello,/longTimeTask"));
    }

    @Test
    void 占位符里的逗号不切() {
        // e495abf 真实踩过的坑：{rand:1000,4000} 曾被切成两条，其中一条原样发出 → 400
        assertArrayEquals(new String[]{"/api/export/report?ms={rand:1000,4000}"},
            HttpLoadTest.splitPaths("/api/export/report?ms={rand:1000,4000}"));
    }

    @Test
    void query值里的逗号不切() {
        assertArrayEquals(new String[]{"/x?ids=1,2,3"}, HttpLoadTest.splitPaths("/x?ids=1,2,3"));
        assertArrayEquals(new String[]{"/a?ids=1,2,3", "/b"},
            HttpLoadTest.splitPaths("/a?ids=1,2,3,/b"));
    }

    @Test
    void 占位符与query值逗号混合() {
        assertArrayEquals(new String[]{"/x?ms={rand:1000,4000}&ids=1,2", "/y"},
            HttpLoadTest.splitPaths("/x?ms={rand:1000,4000}&ids=1,2,/y"));
    }

    @Test
    void 未闭合花括号时剩余逗号全保留() {
        // 笔误要能一路走到 400，而不是被这里悄悄修复
        assertArrayEquals(new String[]{"/x?ms={rand:1000,4000"},
            HttpLoadTest.splitPaths("/x?ms={rand:1000,4000"));
    }

    @Test
    void 分隔符旁有空白照样切() {
        assertArrayEquals(new String[]{"/a", "/b"}, HttpLoadTest.splitPaths(" /a , /b "));
    }

    @Test
    void 空片段被丢弃() {
        assertArrayEquals(new String[]{"/a", "/b"}, HttpLoadTest.splitPaths("/a,,/b,"));
        assertArrayEquals(new String[]{}, HttpLoadTest.splitPaths(""));
    }

    @Test
    void 单条无逗号原样返回() {
        assertArrayEquals(new String[]{"/hello"}, HttpLoadTest.splitPaths("/hello"));
    }

    @Test
    void 嵌套花括号按最外层配对() {
        assertArrayEquals(new String[]{"/x{a{1,2},b}"},
            HttpLoadTest.splitPaths("/x{a{1,2},b}"));
        assertArrayEquals(new String[]{"/x{a{1,2},b}", "/y"},
            HttpLoadTest.splitPaths("/x{a{1,2},b},/y"));
    }
}
