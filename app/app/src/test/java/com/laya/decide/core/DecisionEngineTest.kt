package com.laya.decide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 核心逻辑单测 —— 在 JVM 上跑，不需要模拟器：
 *
 *   gradlew :app:testDebugUnitTest
 *
 * 用例与 server/test/core.test.mjs 是同一套。两端逻辑必须保持一致，
 * 改了任何一边都要同步改另一边，否则手机端校验和服务端校验会打架。
 */
class DecisionEngineTest {

    private val k0 = DecisionEngine.scoreKey(0)
    private val k1 = DecisionEngine.scoreKey(1)
    private val k2 = DecisionEngine.scoreKey(2)

    // -----------------------------------------------------------------------
    // 排名
    // -----------------------------------------------------------------------

    @Test
    fun `rank 按分数降序排列`() {
        val ranked = DecisionEngine.rank(
            listOf("低", "高", "中"),
            mapOf(
                k0 to ScoreAnswer(0.5),
                k1 to ScoreAnswer(2.9),
                k2 to ScoreAnswer(1.5),
            ),
        )
        assertEquals(listOf("高", "中", "低"), ranked.map { it.option })
        assertEquals(2.9 / DecisionEngine.MAX_SCORE, ranked[0].normalized, 1e-9)
    }

    @Test
    fun `rank 同分时保持输入顺序`() {
        val ranked = DecisionEngine.rank(
            listOf("第一", "第二", "第三"),
            mapOf(k0 to ScoreAnswer(2.0), k1 to ScoreAnswer(2.0), k2 to ScoreAnswer(2.0)),
        )
        // 并列的选项不能在界面上随机跳动。
        assertEquals(listOf("第一", "第二", "第三"), ranked.map { it.option })
    }

    @Test
    fun `rank 对缺失分数按 0 处理`() {
        val ranked = DecisionEngine.rank(
            listOf("有分", "缺分"),
            mapOf(k0 to ScoreAnswer(1.0)),
        )
        assertEquals("有分", ranked[0].option)
        assertEquals(0.0, ranked[1].score, 1e-9)
    }

    @Test
    fun `rank 把超范围分数夹回来`() {
        val ranked = DecisionEngine.rank(
            listOf("爆表", "负值"),
            mapOf(k0 to ScoreAnswer(99.0), k1 to ScoreAnswer(-5.0)),
        )
        assertEquals(DecisionEngine.MAX_SCORE, ranked[0].score, 1e-9)
        assertEquals(0.0, ranked[1].score, 1e-9)
    }

    @Test
    fun `rank 对 NaN 不崩`() {
        val ranked = DecisionEngine.rank(
            listOf("正常", "NaN"),
            mapOf(k0 to ScoreAnswer(1.0), k1 to ScoreAnswer(Double.NaN)),
        )
        assertEquals(2, ranked.size)
        assertEquals(0.0, ranked[1].score, 1e-9)
    }

    @Test
    fun `rank 的 index 能指回原选项`() {
        val options = listOf("跳槽", "留下", "读研")
        val ranked = DecisionEngine.rank(
            options,
            mapOf(k0 to ScoreAnswer(2.7), k1 to ScoreAnswer(1.1), k2 to ScoreAnswer(0.4)),
        )
        for (row in ranked) {
            assertEquals(options[row.index], row.option)
        }
    }

    // -----------------------------------------------------------------------
    // 置信度
    // -----------------------------------------------------------------------

    @Test
    fun `分布越集中置信度越高`() {
        assertEquals(1.0, DecisionEngine.confidenceFrom(listOf(0.0, 0.0, 0.0, 1.0)), 1e-9)
        assertEquals(0.0, DecisionEngine.confidenceFrom(listOf(0.25, 0.25, 0.25, 0.25)), 1e-9)
    }

    @Test
    fun `置信度落在 0 到 1 之间`() {
        val cases = listOf(
            listOf(0.1, 0.2, 0.3, 0.4),
            listOf(0.5, 0.5),
            listOf(1.0),
            listOf(0.0, 0.0),
        )
        for (d in cases) {
            val c = DecisionEngine.confidenceFrom(d)
            assertTrue("分布 $d 得到 $c", c in 0.0..1.0)
        }
    }

    @Test
    fun `空分布返回 0`() {
        assertEquals(0.0, DecisionEngine.confidenceFrom(null), 1e-9)
        assertEquals(0.0, DecisionEngine.confidenceFrom(emptyList()), 1e-9)
    }

    // -----------------------------------------------------------------------
    // 决策组装
    // -----------------------------------------------------------------------

    @Test
    fun `前两名接近时标为胶着`() {
        val d = DecisionEngine.assemble(
            listOf("甲", "乙"),
            mapOf(k0 to ScoreAnswer(2.80), k1 to ScoreAnswer(2.78)),
        )
        assertEquals("甲", d.pick)
        assertTrue(d.closeCall)
        assertEquals(MarginLabel.TIGHT, d.marginLabel)
        assertTrue(d.note.contains("甲"))
        assertTrue(d.note.contains("乙"))
    }

    @Test
    fun `差距大时标为明确`() {
        val d = DecisionEngine.assemble(
            listOf("甲", "乙"),
            mapOf(k0 to ScoreAnswer(3.0), k1 to ScoreAnswer(0.2)),
        )
        assertEquals(MarginLabel.CLEAR, d.marginLabel)
        assertFalse(d.closeCall)
    }

    @Test
    fun `中等差距标为有倾向`() {
        // 归一化分差 0.1..0.25 之间
        val d = DecisionEngine.assemble(
            listOf("甲", "乙"),
            mapOf(k0 to ScoreAnswer(2.0), k1 to ScoreAnswer(1.5)),
        )
        assertEquals(MarginLabel.LEANING, d.marginLabel)
        assertFalse(d.closeCall)
    }

    @Test
    fun `单选项没有分差`() {
        val d = DecisionEngine.assemble(listOf("唯一"), mapOf(k0 to ScoreAnswer(1.5)))
        assertEquals("唯一", d.pick)
        assertNull(d.margin)
        assertEquals(MarginLabel.SINGLE, d.marginLabel)
    }

    @Test
    fun `空选项不崩`() {
        val d = DecisionEngine.assemble(emptyList(), emptyMap())
        assertNull(d.pick)
        assertTrue(d.ranked.isEmpty())
    }

    @Test
    fun `推荐项就是排名第一`() {
        val d = DecisionEngine.assemble(
            listOf("a", "b", "c"),
            mapOf(k0 to ScoreAnswer(1.0), k1 to ScoreAnswer(2.5), k2 to ScoreAnswer(0.5)),
        )
        assertNotNull(d.pick)
        assertEquals(d.ranked[0].option, d.pick)
    }

    // -----------------------------------------------------------------------
    // 排名可信度
    // -----------------------------------------------------------------------

    @Test
    fun `所有分数并列时判为不可信`() {
        // 这是最危险的情况：界面上会显示"第一名"，但其实什么都没分出来
        val d = DecisionEngine.assemble(
            listOf("甲", "乙", "丙"),
            mapOf(k0 to ScoreAnswer(0.12), k1 to ScoreAnswer(0.12), k2 to ScoreAnswer(0.12)),
        )
        assertTrue("分数全并列必须判为不可信", DecisionEngine.isRankingUnreliable(d.ranked))
    }

    @Test
    fun `分数拉开时判为可信`() {
        // 实测里模型真正分得出来时极差通常在 0.3 以上
        val d = DecisionEngine.assemble(
            listOf("甲", "乙", "丙"),
            mapOf(k0 to ScoreAnswer(2.58), k1 to ScoreAnswer(2.45), k2 to ScoreAnswer(2.29)),
        )
        assertFalse("极差 0.29 应该判为可信", DecisionEngine.isRankingUnreliable(d.ranked))
    }

    @Test
    fun `首名分差小但整体有层次时仍然可信`() {
        // 2.30/2.25/2.10：首名只领先 0.05，但极差 0.20，整体是有信息量的。
        // 这正是用极差而不是首名分差做判据的原因。
        val d = DecisionEngine.assemble(
            listOf("甲", "乙", "丙"),
            mapOf(k0 to ScoreAnswer(2.30), k1 to ScoreAnswer(2.25), k2 to ScoreAnswer(2.10)),
        )
        assertFalse(DecisionEngine.isRankingUnreliable(d.ranked))
    }

    @Test
    fun `阈值边界`() {
        val just = DecisionEngine.assemble(
            listOf("甲", "乙"),
            mapOf(k0 to ScoreAnswer(0.10), k1 to ScoreAnswer(0.10 - DecisionEngine.UNRELIABLE_SPREAD + 0.001)),
        )
        // 极差 = 0.099 < 0.10 -> 不可信
        assertTrue(DecisionEngine.isRankingUnreliable(just.ranked))

        val over = DecisionEngine.assemble(
            listOf("甲", "乙"),
            mapOf(k0 to ScoreAnswer(0.20), k1 to ScoreAnswer(0.20 - DecisionEngine.UNRELIABLE_SPREAD - 0.001)),
        )
        // 极差 = 0.101 > 0.10 -> 可信
        assertFalse(DecisionEngine.isRankingUnreliable(over.ranked))
    }

    @Test
    fun `单选项不算不可信`() {
        val d = DecisionEngine.assemble(listOf("唯一"), mapOf(k0 to ScoreAnswer(1.5)))
        assertFalse("只有一个选项时无所谓排序可信度", DecisionEngine.isRankingUnreliable(d.ranked))
    }

    @Test
    fun `空排名不崩且不算不可信`() {
        assertFalse(DecisionEngine.isRankingUnreliable(emptyList()))
    }

    // -----------------------------------------------------------------------
    // 校验
    // -----------------------------------------------------------------------

    @Test
    fun `拒绝空情境`() {
        assertTrue(DecisionEngine.validate("", listOf("a", "b")) is DecisionEngine.Validation.Fail)
        assertTrue(DecisionEngine.validate("   ", listOf("a", "b")) is DecisionEngine.Validation.Fail)
    }

    @Test
    fun `拒绝过少和过多的选项`() {
        assertTrue(
            DecisionEngine.validate("情境", listOf("只有一个")) is DecisionEngine.Validation.Fail,
        )
        val tooMany = (1..21).map { "选项$it" }
        assertTrue(DecisionEngine.validate("情境", tooMany) is DecisionEngine.Validation.Fail)
    }

    @Test
    fun `拒绝过长情境`() {
        val long = "字".repeat(DecisionEngine.SITUATION_CHAR_LIMIT + 1)
        assertTrue(DecisionEngine.validate(long, listOf("a", "b")) is DecisionEngine.Validation.Fail)
    }

    @Test
    fun `拒绝过长选项`() {
        val long = "很长".repeat(DecisionEngine.OPTION_CHAR_LIMIT)
        assertTrue(DecisionEngine.validate("情境", listOf("a", long)) is DecisionEngine.Validation.Fail)
    }

    @Test
    fun `接受正常输入`() {
        assertTrue(
            DecisionEngine.validate("该不该接受这个 offer", listOf("接受", "拒绝")) is
                DecisionEngine.Validation.Ok,
        )
    }

    @Test
    fun `清理选项时去空白去空项去重且保序`() {
        val cleaned = DecisionEngine.cleanOptions(listOf("  b  ", "", "a", "b", "   ", "c"))
        assertEquals(listOf("b", "a", "c"), cleaned)
    }

    // -----------------------------------------------------------------------
    // 演示打分器
    // -----------------------------------------------------------------------

    @Test
    fun `演示打分器结果稳定可复现`() {
        val a = DemoScorer.score("我想找个安静的地方看书", "去图书馆")
        val b = DemoScorer.score("我想找个安静的地方看书", "去图书馆")
        assertEquals(a.score, b.score, 1e-12)
    }

    @Test
    fun `演示打分器分数落在合法档位内`() {
        val options = listOf("去爬山", "在家躺着", "去看电影", "找个咖啡馆待着")
        val answers = DemoScorer.scoreAll("周末想放松一下，不想太累", options)
        assertEquals(options.size, answers.size)
        for ((_, v) in answers) {
            assertTrue("分数 ${v.score} 越界", v.score >= 0.0 && v.score <= DecisionEngine.MAX_SCORE)
            val dist = v.distribution
            assertNotNull(dist)
            assertEquals(1.0, dist!!.sum(), 1e-9)
        }
    }

    @Test
    fun `演示打分器配合 assemble 能出完整决策`() {
        val options = listOf("接受 offer", "留在大厂", "继续面试")
        val answers = DemoScorer.scoreAll("薪资降 30% 但有期权", options)
        val d = DecisionEngine.assemble(options, answers)
        assertNotNull(d.pick)
        assertEquals(3, d.ranked.size)
        assertTrue(d.note.isNotBlank())
    }
}
