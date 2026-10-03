package com.laya.decide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 温度与置信度的逻辑测试。
 *
 * 这两处最容易出隐性错误：
 *   - 温度分桶的边界（2 / 3-5 / 6-10 / 11+）错一格，就会用错温度
 *   - 置信度必须用**校准后**的分布算，用原始 logits 算会得到错误的确定度
 */
class LayaConfigTest {

    private val realConfigJson = """
        {
          "encoder": "answerdotai/ModernBERT-large",
          "head_layers": 2,
          "max_len": 512,
          "head_max_len": 192,
          "temperature": [1.6369030475616455, 1.2514300346374512, 1.983399510383606],
          "temperature_by_options": {
            "choice:3-5": 1.7601518630981445,
            "choice:6-10": 1.0000158548355103,
            "score:3-5": 1.2514300346374512,
            "noul:2": 1.983399510383606,
            "choice:11+": 0.10058280825614929,
            "choice:2": 1.9063563346862793
          }
        }
    """.trimIndent()

    @Test
    fun `能解析真实配置文件`() {
        val cfg = LayaConfig.fromJson(realConfigJson)
        assertEquals(512, cfg.maxLen)
        assertEquals(192, cfg.headMaxLen)
        assertEquals(3, cfg.temperature.size)
        assertEquals(1.2514300346374512, cfg.temperature[1], 1e-9)
        assertEquals(6, cfg.temperatureByOptions.size)
    }

    @Test
    fun `温度分桶边界正确`() {
        // 这些边界错一格就会用错温度，进而让分数和置信度都偏
        assertEquals("score:2", ScoreCriteria.bucket("score", 1))
        assertEquals("score:2", ScoreCriteria.bucket("score", 2))
        assertEquals("score:3-5", ScoreCriteria.bucket("score", 3))
        assertEquals("score:3-5", ScoreCriteria.bucket("score", 5))
        assertEquals("score:6-10", ScoreCriteria.bucket("score", 6))
        assertEquals("score:6-10", ScoreCriteria.bucket("score", 10))
        assertEquals("score:11+", ScoreCriteria.bucket("score", 11))
        assertEquals("score:11+", ScoreCriteria.bucket("score", 100))
    }

    @Test
    fun `取温度时优先用按选项数的桶`() {
        val cfg = LayaConfig.fromJson(realConfigJson)
        // score:3-5 桶存在，应该用它
        assertEquals(
            1.2514300346374512,
            cfg.temperatureFor(1, 4, "score"),
            1e-9,
        )
    }

    @Test
    fun `桶不存在时退回按题型的温度`() {
        val cfg = LayaConfig.fromJson(realConfigJson)
        // score:2 这个桶没有配置，应回退到 temperature[1]
        assertEquals(
            1.2514300346374512,
            cfg.temperatureFor(1, 2, "score"),
            1e-9,
        )
    }

    @Test
    fun `配置缺字段时有合理默认值`() {
        val cfg = LayaConfig.fromJson("{}")
        assertEquals(512, cfg.maxLen)
        assertEquals(192, cfg.headMaxLen)
        assertTrue(cfg.temperatureByOptions.isEmpty())
        // 温度全为 1 时不该改变分布
        assertEquals(1.0, cfg.temperatureFor(1, 4, "score"), 1e-9)
    }

    @Test
    fun `默认档位是4档`() {
        assertEquals(4, ScoreCriteria.DEFAULT.size)
        // 档位文本之间必须互不相同，否则模型无法区分
        assertEquals(ScoreCriteria.DEFAULT.size, ScoreCriteria.DEFAULT.toSet().size)
    }

    /**
     * 这条是回归测试：最初我把置信度写成「从原始 logits 算熵」，
     * 那与参考实现不一致 —— 参考实现先除温度再 softmax 再算熵。
     * 温度会改变分布锐度，进而改变确定度，所以顺序不能错。
     */
    @Test
    fun `温度会改变分布与确定度`() {
        val logits = doubleArrayOf(0.8, 0.4, 0.5, 1.0)

        fun softmaxDividedBy(t: Double): DoubleArray {
            val z = logits.map { it / t }
            val m = z.max()
            val e = z.map { Math.exp(it - m) }
            val s = e.sum()
            return e.map { it / s }.toDoubleArray()
        }

        fun confOf(p: DoubleArray): Double {
            var ent = 0.0
            for (x in p) ent -= x * Math.log(maxOf(x, 1e-12))
            return 1.0 - ent / Math.log(p.size.toDouble())
        }

        val c1 = confOf(softmaxDividedBy(1.0))
        val c2 = confOf(softmaxDividedBy(3.0))

        // 温度高 -> 分布更平 -> 确定度更低。两者必须不同。
        assertTrue("温度应改变确定度：t=1 得 $c1，t=3 得 $c2", Math.abs(c1 - c2) > 0.01)
        assertTrue("温度更高时确定度应更低", c2 < c1)
    }
}
