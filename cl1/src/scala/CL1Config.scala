// SPDX-License-Identifier: MulanPSL-2.0

package cl1

object CL1BuildMode {
  private def configValue(name: String): Option[String] = {
    sys.props.get(name).orElse(sys.env.get(name)).map(_.trim).filter(_.nonEmpty)
  }

  private def boolValue(name: String, default: Boolean): Boolean = {
    configValue(name) match {
      case Some(value) =>
        value.toLowerCase match {
          case "1" | "true" | "yes" | "y" | "on"  => true
          case "0" | "false" | "no" | "n" | "off" => false
          case other => throw new IllegalArgumentException(s"$name must be boolean, got '$other'")
        }
      case None => default
    }
  }

  private def normalizePlatform(value: String): String = {
    value.toLowerCase.replace("-", "_") match {
      case "simple" | "simple_soc" => "simple_soc"
      case "full" | "full_soc" => "full_soc"
      case other => throw new IllegalArgumentException(s"CL1_PLATFORM must be simple_soc or full_soc, got '$other'")
    }
  }

  val TEST_MODE: String = configValue("cl1.testMode")
    .orElse(configValue("CL1_TEST_MODE"))
    .getOrElse("bus")
    .toLowerCase

  require(
    TEST_MODE == "bus" || TEST_MODE == "cache",
    s"CL1_TEST_MODE must be 'bus' or 'cache', got '$TEST_MODE'"
  )

  val CACHE_MODE: Boolean = TEST_MODE == "cache"

  private val legacyFullSoc = boolValue("CL1_FULL_SOC_TEST", boolValue("CL1_GLOBAL_FULL_SOC_TEST", false))
  private val legacySimpleSoc = boolValue("CL1_SIMPLE_SOC_TEST", boolValue("CL1_GLOBAL_SIMPLE_SOC_TEST", true))
  val PLATFORM: String = configValue("CL1_PLATFORM")
    .orElse(configValue("CL1_ADDRESS_PROFILE"))
    .map(normalizePlatform)
    .getOrElse(if (legacyFullSoc || !legacySimpleSoc) "full_soc" else "simple_soc")

  def bool(name: String, default: Boolean): Boolean = boolValue(name, default)

  def int(name: String, default: Int): Int =
    configValue(name).map(_.toInt).getOrElse(default)

  def string(name: String, default: String): String =
    configValue(name).getOrElse(default)
}

object CL1Technology {
  val CX55 = "CX55"
  val SMIC55 = "SMIC55"
  val SMIC100 = "SMIC100"

  private val supported = Seq(CX55, SMIC55, SMIC100)

  def normalize(value: String): String = {
    val normalized = value.trim.toUpperCase.replace("-", "_")
    normalized match {
      case CX55 | SMIC55 | SMIC100 => normalized
      case _ =>
        throw new IllegalArgumentException(
          s"CL1_TECHNOLOGY must be one of ${supported.mkString(", ")}, got '$value'"
        )
    }
  }

  def useCx55Memory(value: String): Boolean = normalize(value) == CX55
  def useSmic100Memory(value: String): Boolean = normalize(value) == SMIC100
}

object CL1BuildProfile {
  private val selectedSimpleSoc = CL1BuildMode.PLATFORM == "simple_soc"
  private val selectedFullSoc = CL1BuildMode.PLATFORM == "full_soc"
  val simpleSocTest = CL1BuildMode.bool("CL1_GLOBAL_SIMPLE_SOC_TEST", selectedSimpleSoc)
  val fullSocTest  = CL1BuildMode.bool("CL1_GLOBAL_FULL_SOC_TEST", selectedFullSoc)
}

// Synthesis configuration: synthesis flow mode and foundry SRAM macro choices.
object CL1SynthesisConfig {
  val syn = CL1BuildMode.bool("CL1_GLOBAL_SYN", CL1BuildMode.bool("CL1_SYN", !CL1BuildMode.CACHE_MODE))
  val SramFoundary = syn
  val Technology = CL1Technology.normalize(CL1BuildMode.string("CL1_TECHNOLOGY", CL1Technology.CX55))
}

// Processor configuration: architectural constants, SoC-facing shape,
// reset policy, memory implementation and core micro-architecture knobs.
object CL1ProcessorConfig {
  private val platform = PlatformAddressMaps.selected
  val BOOT_ADDR  = platform.bootAddrLiteral
  val TVEC_ADDR  = platform.trapVectorLiteral
  val BUS_WIDTH  = 32
  val DBG_ENTRYADDR = "h800"
  val DBG_EXCP_BASE = "h800"
  val MDU_SHAERALU = false
  val MDU_IMPL = CL1BuildMode.string("CL1_MDU_IMPL", "iterative").toLowerCase.replace("-", "_")
  require(
    MDU_IMPL == "iterative" || MDU_IMPL == "fast_mul",
    s"CL1_MDU_IMPL must be iterative or fast_mul, got '$MDU_IMPL'"
  )
  val WB_PIPESTAGE = true
  val HAS_ICACHE   = CL1BuildMode.bool("CL1_HAS_ICACHE", CL1BuildMode.CACHE_MODE)
  val HAS_DCACHE   = CL1BuildMode.bool("CL1_HAS_DCACHE", CL1BuildMode.CACHE_MODE)
  val RST_ACTIVELOW = true
  val RST_ASYNC     = true
  val EXPOSE_CORE_BUS = CL1BuildMode.bool("CL1_EXPOSE_CORE_BUS", !CL1BuildMode.CACHE_MODE)
  val SOC_D64      = if(CL1BuildProfile.fullSocTest) true else false

  require(
    !(EXPOSE_CORE_BUS && (HAS_ICACHE || HAS_DCACHE)),
    "cache instances are unreachable when EXPOSE_CORE_BUS=true; use CL1_TEST_MODE=cache or disable caches"
  )
}

// Verification configuration: RVFI/formal/difftest and verification-only sizing.
object CL1VerificationConfig {
  val SOC_DIFF     = CL1BuildMode.bool("CL1_SOC_DIFF", CL1BuildProfile.fullSocTest)
  val DIFFTEST     = if(CL1BuildProfile.simpleSocTest) false else false
  val difftest     = DIFFTEST
  val FORMAL_VERIF = CL1BuildMode.bool("CL1_FORMAL_VERIF", false)
  val RISCV_FORMAL_ALTOPS = CL1BuildMode.bool("CL1_RISCV_FORMAL_ALTOPS", false)
  val FORMAL_CACHE_IDXW = CL1BuildMode.int("CL1_FORMAL_CACHE_IDXW", 7)
}

// Low-power configuration: clock gates and reset-saving options.
object CL1PowerSaveConfig {
  val MODPOWERCFG = false
  val CKG_EN     = false
  val MDU_CKG_EN  = if (MODPOWERCFG) true else false
  val DCACHE_CKG_EN = if (MODPOWERCFG) true else false
  val LSU_CKG_EN    = if (MODPOWERCFG) true else false
  val RF_NORESET    = true
}

// Compatibility facade for existing imports. New code should prefer the
// classified config objects above.
object CL1Config {
  val BOOT_ADDR = CL1ProcessorConfig.BOOT_ADDR
  val TVEC_ADDR = CL1ProcessorConfig.TVEC_ADDR
  val BUS_WIDTH = CL1ProcessorConfig.BUS_WIDTH
  val CKG_EN = CL1PowerSaveConfig.CKG_EN
  val difftest = CL1VerificationConfig.difftest
  val DIFFTEST = CL1VerificationConfig.DIFFTEST
  val DBG_ENTRYADDR = CL1ProcessorConfig.DBG_ENTRYADDR
  val DBG_EXCP_BASE = CL1ProcessorConfig.DBG_EXCP_BASE
  val MDU_SHAERALU = CL1ProcessorConfig.MDU_SHAERALU
  val MDU_IMPL = CL1ProcessorConfig.MDU_IMPL
  val WB_PIPESTAGE = CL1ProcessorConfig.WB_PIPESTAGE
  val HAS_ICACHE = CL1ProcessorConfig.HAS_ICACHE
  val HAS_DCACHE = CL1ProcessorConfig.HAS_DCACHE
  val RST_ACTIVELOW = CL1ProcessorConfig.RST_ACTIVELOW
  val RST_ASYNC = CL1ProcessorConfig.RST_ASYNC
  val SOC_DIFF = CL1VerificationConfig.SOC_DIFF
  val SramFoundary = CL1SynthesisConfig.SramFoundary
  val SOC_D64 = CL1ProcessorConfig.SOC_D64
  val Technology = CL1SynthesisConfig.Technology
  val FORMAL_VERIF = CL1VerificationConfig.FORMAL_VERIF
  val RISCV_FORMAL_ALTOPS = CL1VerificationConfig.RISCV_FORMAL_ALTOPS
  val EXPOSE_CORE_BUS = CL1ProcessorConfig.EXPOSE_CORE_BUS
  val FORMAL_CACHE_IDXW = CL1VerificationConfig.FORMAL_CACHE_IDXW
}
