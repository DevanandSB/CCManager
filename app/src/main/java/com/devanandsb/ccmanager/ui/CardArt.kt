package com.devanandsb.ccmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devanandsb.ccmanager.data.Card

/**
 * Logo-free card art for popular Indian cards: each is an approximation of the real card's colours and
 * main motif (no bank marks, network logos or text), drawn cleanly in vector precision so every card
 * looks sharp and metallic at any size.
 *
 * Which art a card gets: [Card.art] null = "auto" (matched from bank + nickname, else bank colours),
 * [BANK_COLOURS] = always the bank-colour gradient, otherwise an art id.
 */
enum class Motif {
    NONE, SHEEN, GLOW, RAYS, CHEVRON, ARROW, PYRAMID, RIBBON, STREAK, ORB, GLOBE, DOTS, FACETS, PIXELS,
    SWIRL, BAND, LINES, BRUSHED, RIPPLES, RINGS, SPARKLE, MOSAIC, SPLIT, HALF_SPLIT, ARC, CROSS, WAVES,
}

class CardArt(
    val id: String,
    val bank: String,
    val name: String,
    /** Background gradient, top-left to bottom-right (or left to right when [horizontal]). */
    val colors: List<Long>,
    val motif: Motif = Motif.NONE,
    val accent: List<Long> = emptyList(),
    /** Nickname patterns; every word of one pattern must appear. Longer patterns win. */
    val match: List<String> = emptyList(),
    /** Light art: card text switches to dark ink. */
    val light: Boolean = false,
    /** Motif placement (fractions of width / height) for motifs that sit somewhere. */
    val x: Float = 0.75f,
    val y: Float = 0.5f,
    val horizontal: Boolean = false,
    /** The only design of a single-product issuer (slice, Roar): matches any nickname. */
    val anyName: Boolean = false,
)

const val BANK_COLOURS = "bank"

object CardArts {
    private const val HDFC = "HDFC Bank"
    private const val SBI = "SBI Card"
    private const val ICICI = "ICICI Bank"
    private const val AXIS = "Axis Bank"
    private const val AU = "AU Bank"
    private const val CSB = "CSB Bank"
    private const val FEDERAL = "Federal Bank"
    private const val PAYTM = "Paytm"

    val all: List<CardArt> = listOf(
        // HDFC Bank
        CardArt("hdfc_millennia", HDFC, "Millennia", listOf(0xFF0B1E4F, 0xFF1745A8, 0xFF0E296B), Motif.MOSAIC, listOf(0xFF4788FF, 0xFF8FB8FF, 0xFF2054C8), listOf("millennia")),
        CardArt("hdfc_regalia_gold", HDFC, "Regalia Gold", listOf(0xFF23150B, 0xFF3E2614, 0xFF1D1108), Motif.BAND, listOf(0xFFD6A343, 0xFFF7DA88), listOf("regalia gold", "gold"), horizontal = true),
        CardArt("hdfc_regalia", HDFC, "Regalia", listOf(0xFF1F120D, 0xFF3A2016), Motif.BAND, listOf(0xFFC2834A, 0xFFE5AE7A), listOf("regalia"), horizontal = true),
        CardArt("hdfc_infinia", HDFC, "Infinia", listOf(0xFF050E26, 0xFF0F2659, 0xFF081434), Motif.WAVES, listOf(0xFF5D89DB), listOf("infinia")),
        CardArt("hdfc_diners_black", HDFC, "Diners Club Black", listOf(0xFF0B0C10, 0xFF171920), Motif.DOTS, listOf(0xFFDDE2EC), listOf("diners", "diners black", "dcb")),
        CardArt("hdfc_diners_privilege", HDFC, "Diners Club Privilege", listOf(0xFF2E1228, 0xFF592246), Motif.DOTS, listOf(0xFFF3C6DF), listOf("dcp", "diners privilege", "privilege")),
        CardArt("hdfc_phonepe_ultimo", HDFC, "PhonePe Ultimo", listOf(0xFF0E0B14, 0xFF1C1428), Motif.GLOW, listOf(0xFF6B3FD6, 0xFF2A1650), listOf("ultimo", "phonepe ultimo"), x = 0.88f, y = 0.14f),
        CardArt("hdfc_phonepe_uno", HDFC, "PhonePe UNO", listOf(0xFF4B1F8F, 0xFF7A3FD0), Motif.SHEEN, listOf(0xFFC9A8FF), listOf("uno", "phonepe uno")),
        CardArt("hdfc_paytm", HDFC, "Paytm HDFC", listOf(0xFF00205B, 0xFF003B95), Motif.RIBBON, listOf(0xFF00BAF2, 0xFF52D7FF), listOf("paytm", "paytm hdfc", "paytm mobile")),
        CardArt("hdfc_paytm_select", HDFC, "Paytm HDFC Select", listOf(0xFF091224, 0xFF132447), Motif.STREAK, listOf(0xFF00BAF2, 0xFF0077C8), listOf("paytm select", "paytm hdfc select")),
        CardArt("hdfc_biz_black", HDFC, "Biz Black", listOf(0xFF0C0D10, 0xFF191B20), Motif.ARROW, listOf(0xFF5E6473), listOf("biz black")),
        CardArt("hdfc_biz_power", HDFC, "Biz Power", listOf(0xFF151335, 0xFF221E52), Motif.ARROW, listOf(0xFF6A5CF0), listOf("biz power", "biz")),
        CardArt("hdfc_swiggy", HDFC, "Swiggy", listOf(0xFFFF6D1A, 0xFFE63274, 0xFF4A2FD6), Motif.WAVES, listOf(0xFFFFFFFF), match = listOf("swiggy", "swiggy orange", "ornge")),
        CardArt("hdfc_swiggy_blck", HDFC, "Swiggy BLCK", listOf(0xFF0E0E10, 0xFF17171B), Motif.GLOW, listOf(0xFFFF5A1F, 0xFFB8201A), listOf("swiggy black", "swiggy blck", "blck"), x = 0.92f, y = 0.88f),
        CardArt("hdfc_tata_neu_infinity", HDFC, "Tata Neu Infinity", listOf(0xFF120826, 0xFF281252), Motif.ARC, listOf(0xFF8E4DFF, 0xFFD23CC4), listOf("neu", "neu infinity", "tata neu")),
        CardArt("hdfc_tata_neu_plus", HDFC, "Tata Neu Plus", listOf(0xFF198FE6, 0xFF5D46DF), Motif.ARC, listOf(0xFF63D9FF, 0xFFA26DFF), listOf("neu plus")),
        CardArt("hdfc_upi_rupay", HDFC, "UPI RuPay", listOf(0xFF11142E, 0xFF1B1F45), Motif.PIXELS, listOf(0xFFFF4F9A, 0xFF3D7BFF), listOf("upi", "rupay", "biz")),
        CardArt("hdfc_moneyback", HDFC, "MoneyBack+", listOf(0xFF1280B8, 0xFF26A9E0), Motif.CROSS, listOf(0xFFE53935, 0xFF102863), listOf("moneyback", "money back")),
        CardArt("hdfc_freedom", HDFC, "Freedom", listOf(0xFF1554B5, 0xFF2C7BE5), Motif.ARROW, listOf(0xFFE53935), listOf("freedom")),
        CardArt("hdfc_marriott", HDFC, "Marriott Bonvoy", listOf(0xFF0E0F12, 0xFF1D1E24), Motif.STREAK, listOf(0xFFD1AC64), listOf("marriott", "bonvoy")),
        CardArt("hdfc_indianoil", HDFC, "IndianOil", listOf(0xFFF6D7A7, 0xFFD9A35C), Motif.SHEEN, listOf(0xFFFFFFFF), listOf("indianoil", "indian oil", "iocl"), light = true),
        CardArt("hdfc_pixel", HDFC, "Pixel Play", listOf(0xFF7FD3D0, 0xFFB7EEE8), Motif.MOSAIC, listOf(0xFFFF6FA8, 0xFFFFD84D, 0xFF3AA0FF), listOf("pixel", "pixel play"), light = true),
        CardArt("hdfc_pixel_go", HDFC, "Pixel Go", listOf(0xFF3EC3F2, 0xFF7FD8F7), Motif.SHEEN, listOf(0xFFFFFFFF), listOf("pixel go"), light = true),

        // SBI Card
        CardArt("sbi_simplyclick", SBI, "SimplyCLICK", listOf(0xFF0A2E22, 0xFF165B40), Motif.LINES, listOf(0xFF6CD6A4), listOf("simplyclick", "simply click", "click")),
        CardArt("sbi_simplysave", SBI, "SimplySAVE", listOf(0xFF113987, 0xFF266AC7), Motif.RAYS, listOf(0xFF8FC2FF), listOf("simplysave", "simply save", "save")),
        CardArt("sbi_cashback", SBI, "Cashback", listOf(0xFF2B1478, 0xFF4F24B8), Motif.CHEVRON, listOf(0xFFFFD21F), listOf("cashback", "cash back")),
        CardArt("sbi_elite", SBI, "Elite", listOf(0xFF131110, 0xFF25201A), Motif.BRUSHED, listOf(0xFFD1AC76), listOf("elite")),
        CardArt("sbi_prime", SBI, "Prime", listOf(0xFF0E0E14, 0xFF1A1A24), Motif.RIBBON, listOf(0xFFFF4D6D, 0xFFFFC93C, 0xFF3DDC97, 0xFF4D8BFF, 0xFFB45CFF), listOf("prime")),
        CardArt("sbi_octane", SBI, "BPCL Octane", listOf(0xFF0F0D0A, 0xFF1E1A12), Motif.STREAK, listOf(0xFFF2C14E, 0xFF8A5A12), listOf("octane", "bpcl")),
        CardArt("sbi_irctc", SBI, "IRCTC", listOf(0xFFEC7219, 0xFF258C52), Motif.SHEEN, listOf(0xFFFFFFFF), listOf("irctc", "railway")),
        CardArt("sbi_pulse", SBI, "Pulse", listOf(0xFF180C2B, 0xFF360D28), Motif.RIPPLES, listOf(0xFFE5395F), listOf("pulse"), x = 0.85f, y = 0.6f),
        CardArt("paytm_first", SBI, "Paytm SBI", listOf(0xFF009AE2, 0xFF0055A8), Motif.SHEEN, listOf(0xFFFFFFFF, 0xFF7BE3FF), listOf("paytm", "paytm first", "paytm sbi")),
        CardArt("paytm_select", SBI, "Paytm SBI Select", listOf(0xFF091022, 0xFF162448), Motif.STREAK, listOf(0xFF00BAF2, 0xFF4B8BFF), listOf("paytm select", "paytm sbi select")),

        // ICICI Bank
        CardArt("icici_platinum", ICICI, "Platinum", listOf(0xFF101114, 0xFF1F2127), Motif.BRUSHED, listOf(0xFFD9DCE0), listOf("platinum")),
        CardArt("icici_amazon", ICICI, "Amazon Pay", listOf(0xFF20242B, 0xFF101216), Motif.BRUSHED, listOf(0xFFFF9900, 0xFFFFFFFF), listOf("amazon", "apay")),
        CardArt("icici_sapphiro", ICICI, "Sapphiro", listOf(0xFF09307A, 0xFF3E98E8), Motif.FACETS, match = listOf("sapphiro", "sapphire")),
        CardArt("icici_rubyx", ICICI, "Rubyx", listOf(0xFF690619, 0xFFC0102E), Motif.FACETS, match = listOf("rubyx", "ruby")),
        CardArt("icici_coral", ICICI, "Coral", listOf(0xFF560A0C, 0xFFC83A20), Motif.ARC, listOf(0xFFFF7A45, 0xFF8A1A0A), listOf("coral")),
        CardArt("icici_emeralde", ICICI, "Emeralde", listOf(0xFF04241B, 0xFF0D4A3A), Motif.BRUSHED, listOf(0xFF44CA9F), listOf("emeralde", "emerald")),
        CardArt("icici_mmt", ICICI, "MakeMyTrip", listOf(0xFF281006, 0xFF85360E), Motif.GLOW, listOf(0xFFFFB347, 0xFFB8561A), listOf("makemytrip", "mmt"), x = 0.7f, y = 0.9f),
        CardArt("icici_hpcl", ICICI, "HPCL Super Saver", listOf(0xFFE9F1FA, 0xFFCFE0F5), Motif.MOSAIC, listOf(0xFF5BC0EB, 0xFFFDE74C, 0xFF9BC53D, 0xFFE55934), listOf("hpcl", "super saver"), light = true),

        // Axis Bank
        CardArt("axis_privilege", AXIS, "Privilege", listOf(0xFF111114, 0xFF1D1D22), Motif.PYRAMID, listOf(0xFFE0337A, 0xFF6E1238), listOf("privilege")),
        CardArt("axis_airtel", AXIS, "Airtel", listOf(0xFF141518, 0xFF222429), Motif.RIBBON, listOf(0xFFE11B22, 0xFFF2F3F5, 0xFF8A8E93), listOf("airtel")),
        CardArt("axis_atlas", AXIS, "Atlas", listOf(0xFF0D0D10, 0xFF17171B), Motif.GLOBE, listOf(0xFFE23A86, 0xFF5A0E33), listOf("atlas"), x = 0.74f, y = 0.54f),
        CardArt("axis_magnus", AXIS, "Magnus", listOf(0xFF0F0F12, 0xFF1B1C20), Motif.SWIRL, listOf(0xFFF0479A, 0xFF5C0F33), listOf("magnus")),
        CardArt("axis_ace", AXIS, "ACE", listOf(0xFF0E0B12, 0xFF191420), Motif.WAVES, listOf(0xFFE0408A), listOf("ace")),
        CardArt("axis_flipkart", AXIS, "Flipkart", listOf(0xFF0C0E1A, 0xFF16182C), Motif.RIBBON, listOf(0xFF3D5AFE, 0xFF8E44FF, 0xFFFF4FA0), listOf("flipkart")),
        CardArt("axis_select", AXIS, "Select", listOf(0xFF180C13, 0xFF29111E), Motif.RIPPLES, listOf(0xFFC2185B), listOf("select"), x = 0.75f, y = 0.55f),
        CardArt("axis_neo", AXIS, "Neo", listOf(0xFF120A10, 0xFF1E0F1A), Motif.RINGS, listOf(0xFFE0337A), listOf("neo")),
        CardArt("axis_reserve", AXIS, "Reserve", listOf(0xFF0B0B0D, 0xFF141418), Motif.ORB, listOf(0xFFFF4F9A, 0xFF8A0F45), listOf("reserve"), x = 0.3f, y = 0.5f),

        // AU Bank
        CardArt("au_paytm", AU, "Paytm AU", listOf(0xFFF2D785, 0xFFC99834, 0xFF9C6C19), Motif.BAND, listOf(0xFFFFF6D1, 0xFF00BAF2), listOf("paytm", "paytm au", "paytm gold"), light = true, horizontal = true),
        CardArt("au_lit", AU, "LIT", listOf(0xFFF2EEFC, 0xFFE1D6F9), Motif.MOSAIC, listOf(0xFFB794F4, 0xFFFF8FB1, 0xFF7FD3FF), listOf("lit"), light = true),
        CardArt("au_zenith", AU, "Zenith+", listOf(0xFF072318, 0xFF103F2E), Motif.STREAK, listOf(0xFFD4AF37), listOf("zenith+", "zenith plus")),
        CardArt("au_zenith_classic", AU, "Zenith", listOf(0xFF121318, 0xFF262934), Motif.STREAK, listOf(0xFFC0C6D0), listOf("zenith")),
        CardArt("au_ixigo", AU, "ixigo AU", listOf(0xFF0B3A8C, 0xFF175EC2), Motif.ARC, listOf(0xFFFF6D00, 0xFFFF9E40), listOf("ixigo", "au ixigo")),
        CardArt("au_vetta", AU, "Vetta", listOf(0xFF081A38, 0xFF133368), Motif.FACETS, match = listOf("vetta")),
        CardArt("au_altura", AU, "Altura", listOf(0xFFD6DCE5, 0xFF8E98A8), Motif.BRUSHED, listOf(0xFFFFFFFF), listOf("altura", "altura plus", "altura+"), light = true),
        CardArt("au_xcite", AU, "Xcite", listOf(0xFF2D165E, 0xFF5D2BA8), Motif.RIBBON, listOf(0xFFFF7EB3, 0xFF7AD7FF), listOf("xcite", "xcite ace", "xcite ultra")),

        // CSB Bank (Jupiter Edge+ & Edge)
        CardArt(
            "csb_edge_plus", CSB, "Edge+ Jupiter",
            listOf(0xFFEEF3F9, 0xFFD7E2F0), Motif.HALF_SPLIT,
            listOf(0xFFFF5421, 0xFFFF8A2B),
            listOf("edge+", "edge plus", "jupiter edge+", "jupiter edge plus", "jupiter", "csb edge", "edge"),
            light = true,
        ),
        CardArt(
            "csb_edge", CSB, "Edge (Black)",
            listOf(0xFF0E1015, 0xFF191C24), Motif.HALF_SPLIT,
            listOf(0xFFFF5421, 0xFFFF7A30),
            listOf("edge black", "jupiter edge black"),
        ),

        // Paytm
        CardArt("paytm_au_brand", PAYTM, "Paytm AU Gold", listOf(0xFFF2D785, 0xFFC99834, 0xFF9C6C19), Motif.BAND, listOf(0xFFFFF6D1, 0xFF00BAF2), listOf("paytm au", "au", "gold"), light = true, horizontal = true),
        CardArt("paytm_hdfc_brand", PAYTM, "Paytm HDFC", listOf(0xFF00205B, 0xFF003B95), Motif.RIBBON, listOf(0xFF00BAF2, 0xFF52D7FF), listOf("paytm hdfc", "hdfc")),
        CardArt("paytm_sbi_brand", PAYTM, "Paytm SBI", listOf(0xFF009AE2, 0xFF0055A8), Motif.SHEEN, listOf(0xFFFFFFFF), listOf("paytm sbi", "sbi", "first"), anyName = true),
        CardArt("paytm_select_brand", PAYTM, "Paytm Select", listOf(0xFF0B1328, 0xFF16274E), Motif.STREAK, listOf(0xFF00BAF2, 0xFF4B8BFF), listOf("select")),

        // Federal Bank
        CardArt("scapia", FEDERAL, "Scapia", listOf(0xFFE93B25, 0xFFF46B38), Motif.WAVES, listOf(0xFFFFB89E), listOf("scapia")),
        CardArt("jupiter_edge", FEDERAL, "Jupiter Edge+", listOf(0xFFEEF3F9, 0xFFD7E2F0), Motif.HALF_SPLIT, listOf(0xFFFF5421, 0xFFFF8A2B), listOf("jupiter", "jupiter edge", "edge+", "edge plus", "edge"), light = true),
        CardArt("jupiter_sigma", FEDERAL, "Jupiter Sigma", listOf(0xFF0C1B33, 0xFF1A3A6E), Motif.RIPPLES, listOf(0xFF4A9BFF), listOf("jupiter sigma", "sigma"), x = 0.8f, y = 0.5f),
        CardArt("fi_money", FEDERAL, "Fi Money", listOf(0xFF003E4F, 0xFF006B7D), Motif.WAVES, listOf(0xFF00D4AA), listOf("fi money", "fi")),

        // Others
        CardArt("kotak_811", "Kotak", "811", listOf(0xFF0E1F2E, 0xFF173246), Motif.RIPPLES, listOf(0xFF5A8AAA), listOf("811"), x = 0f, y = 1f),
        CardArt("kotak_myntra", "Kotak", "Myntra", listOf(0xFFFFB38A, 0xFFFF6F91), Motif.SHEEN, listOf(0xFFFFFFFF), listOf("myntra"), light = true),
        CardArt("yes_elite", "Yes Bank", "Elite+", listOf(0xFF0A1C5A, 0xFF1A3B9E), Motif.RAYS, listOf(0xFF6EA2FF), listOf("elite")),
        CardArt("idfc_millennia", "IDFC FIRST", "Millennia", listOf(0xFF5A0F1E, 0xFF9B1B30), Motif.GLOW, listOf(0xFFFF5A6E, 0xFF9B1B30), listOf("millennia"), x = 0.85f, y = 0.15f),
        CardArt("idfc_select", "IDFC FIRST", "Select", listOf(0xFF8E1020, 0xFFC8102E), Motif.SHEEN, listOf(0xFFFF8A95), listOf("select")),
        CardArt("idfc_wealth", "IDFC FIRST", "Wealth", listOf(0xFF0E0E10, 0xFF1B1B1F), Motif.BRUSHED, listOf(0xFFFFFFFF), listOf("wealth")),
        CardArt("idfc_wow", "IDFC FIRST", "WOW!", listOf(0xFF1E3A5F, 0xFF2A5298), Motif.WAVES, listOf(0xFF5B9BFF), listOf("wow")),
        CardArt("idfc_first_classic", "IDFC FIRST", "Classic", listOf(0xFF7A0A1E, 0xFFAD1A30), Motif.SHEEN, listOf(0xFFFF8A95), listOf("classic")),
        CardArt("amex_plat_travel", "Amex", "Platinum Travel", listOf(0xFFD3D7DC, 0xFF8E949B), Motif.SHEEN, listOf(0xFFFFFFFF), listOf("platinum", "platinum travel", "plat travel"), light = true),
        CardArt("amex_mrcc", "Amex", "Membership Rewards", listOf(0xFFB4C1CE, 0xFF7C8FA4), Motif.SHEEN, listOf(0xFFFFFFFF), listOf("mrcc", "membership", "rewards"), light = true),
        CardArt("amex_gold", "Amex", "Gold Charge", listOf(0xFFE8C66A, 0xFFB8862E), Motif.SHEEN, listOf(0xFFFFF3C4), listOf("gold"), light = true),
        CardArt("amex_smartearn", "Amex", "SmartEarn", listOf(0xFF0F6FC6, 0xFF3FA2F0), Motif.SHEEN, listOf(0xFFFFFFFF), listOf("smartearn", "smart earn")),
        CardArt("hsbc_live", "HSBC", "Live+", listOf(0xFFE9A9B3, 0xFFC8102E, 0xFF7A0A1E), Motif.SPLIT, listOf(0xFF8E0C22), listOf("live")),
        CardArt("hsbc_platinum", "HSBC", "Platinum", listOf(0xFFB9BDC3, 0xFF9EA2A8), Motif.FACETS, match = listOf("platinum"), light = true),
        CardArt("sc_ultimate", "Standard Chartered", "Ultimate", listOf(0xFF0C0C0E, 0xFF17171A), Motif.SPARKLE, listOf(0xFFD4AF37), listOf("ultimate")),
        CardArt("sc_smart", "Standard Chartered", "Smart", listOf(0xFFE9ECEF, 0xFFCED4DA), Motif.ARROW, listOf(0xFFA8B0B8), listOf("smart"), light = true),
        CardArt("indusind_legend", "IndusInd", "Legend", listOf(0xFF101012, 0xFF2A2B2F), Motif.BRUSHED, listOf(0xFFC0C0C0), listOf("legend")),
        CardArt("indusind_tiger", "IndusInd", "Tiger", listOf(0xFF0B0B0C, 0xFF151517), Motif.LINES, listOf(0xFFE0E0E0), listOf("tiger")),
        CardArt("indusind_nexxt", "IndusInd", "Nexxt", listOf(0xFF0D1B2A, 0xFF1A2F44), Motif.PIXELS, listOf(0xFF4FC3F7, 0xFF7C4DFF), listOf("nexxt")),
        CardArt("indusind_eazydiner", "IndusInd", "EazyDiner Platinum", listOf(0xFF1A0A14, 0xFF2D1020), Motif.GLOW, listOf(0xFFE91E8C, 0xFF6A0F45), listOf("eazydiner", "eazy diner"), x = 0.75f, y = 0.3f),
        CardArt("onecard_metal", "OneCard", "Metal", listOf(0xFF141416, 0xFF24252A), Motif.BRUSHED, listOf(0xFFFFFFFF), anyName = true),
        CardArt("slice", "Slice", "slice", listOf(0xFF5A1FD1, 0xFF8B45F0), Motif.SHEEN, listOf(0xFFB98CFF), anyName = true),
        CardArt("roar", "Unity Bank (Roar)", "Roarbank", listOf(0xFF26272B, 0xFF08080A), Motif.GLOW, listOf(0xFF3A3B40, 0xFF26272B), anyName = true, x = 0.25f, y = 0.15f),

        // More variants
        CardArt("sbi_phonepe_black", SBI, "PhonePe SELECT Black", listOf(0xFF0B0B10, 0xFF15141E), Motif.RIBBON, listOf(0xFF1FC8C8, 0xFF7A4DFF), listOf("phonepe black", "select black", "phonepe select")),
        CardArt("sbi_phonepe_purple", SBI, "PhonePe PURPLE", listOf(0xFF2A0F55, 0xFF4E1F96), Motif.GLOW, listOf(0xFF9D6BFF, 0xFF4E1F96), listOf("phonepe purple", "purple"), x = 0.7f, y = 0.4f),
        CardArt("sbi_miles_elite", SBI, "Miles Elite", listOf(0xFFE9C9B4, 0xFFC9967A), Motif.RAYS, listOf(0xFFFFF1E6), listOf("miles", "miles elite"), light = true),
        CardArt("sbi_aurum", SBI, "Aurum", listOf(0xFF0B0B0C, 0xFF161617), Motif.SPARKLE, listOf(0xFFD9B45A), listOf("aurum")),
        CardArt("icici_times_black", ICICI, "Times Black", listOf(0xFF0A0A0B, 0xFF18181A), Motif.BRUSHED, listOf(0xFFB8B8B8), listOf("times", "times black")),
        CardArt("hsbc_premier", "HSBC", "Premier", listOf(0xFF0F1226, 0xFF1E2448), Motif.FACETS, match = listOf("premier")),
        CardArt("hsbc_travelone", "HSBC", "TravelOne", listOf(0xFF0C0C0D, 0xFF19191B), Motif.CHEVRON, listOf(0xFF6B6E73), listOf("travelone", "travel one", "t1")),
        CardArt("yes_marquee", "Yes Bank", "Marquee", listOf(0xFF111114, 0xFF222228), Motif.SWIRL, listOf(0xFFB9BEC6, 0xFF3A3D44), listOf("marquee")),
        CardArt("yes_reserv", "Yes Bank", "Reserv", listOf(0xFF0A1238, 0xFF16246A), Motif.SHEEN, listOf(0xFF6F8CFF), listOf("reserv", "reserve")),
        CardArt("yes_kiwi", "Yes Bank", "Kiwi", listOf(0xFF8BD650, 0xFF5DBB3A), Motif.SHEEN, listOf(0xFFE8FFD6), listOf("kiwi"), light = true),
        CardArt("bob_eterna", "BOB Card", "Eterna", listOf(0xFF071A33, 0xFF0D2C52), Motif.WAVES, listOf(0xFF2EC4E6), listOf("eterna")),
        CardArt("bob_premier", "BOB Card", "Premier", listOf(0xFF5A0E14, 0xFF9B1C24), Motif.SHEEN, listOf(0xFFFF8A8A), listOf("premier")),
        CardArt("amex_platinum_charge", "Amex", "Platinum Charge", listOf(0xFFDADDE1, 0xFFA3A8AE), Motif.BRUSHED, listOf(0xFFFFFFFF), listOf("platinum charge", "plat charge", "charge"), light = true),
        CardArt("amex_platinum_reserve", "Amex", "Platinum Reserve", listOf(0xFFB7BCC2, 0xFF7E848B), Motif.SHEEN, listOf(0xFFFFFFFF), listOf("platinum reserve", "reserve"), light = true),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String?): CardArt? = id?.let { byId[it] }

    fun forBank(issuer: String): List<CardArt> = all.filter { it.bank == issuer }

    /** Best art for a bank + nickname, or null when nothing fits. */
    fun match(issuer: String, nickname: String): CardArt? {
        val name = nickname.lowercase().replace(Regex("[^a-z0-9+ ]"), " ")
        val words = name.split(' ').filter { it.isNotBlank() }.toSet()
        fun hits(pattern: String) = pattern.split(' ').all { w -> words.contains(w) || (w.length >= 4 && name.contains(w)) }
        val candidates = forBank(issuer)
        candidates.mapNotNull { a ->
            a.match.filter(::hits).maxOfOrNull { it.split(' ').size }?.let { a to it }
        }.maxByOrNull { it.second }?.let { return it.first }
        return candidates.firstOrNull { it.anyName }
    }

    /** The art a card shows: its chosen one, or (auto) the best match; null = bank colours. */
    fun resolve(card: Card): CardArt? = when (card.art) {
        null -> match(card.issuer, card.nickname)
        BANK_COLOURS -> null
        else -> byId(card.art)
    }
}

/** Card text colour for this card: from its art when it has one, else from the bank colour. */
fun cardInkFor(card: Card): Color {
    val art = CardArts.resolve(card) ?: return cardInk(card.color)
    return if (art.light) Color(0xFF15171C) else Color.White
}

/**
 * The card's background, filling its (clipped) parent: the chosen or matched art with a slight blur,
 * or the bank-colour gradient.
 */
@Composable
fun CardSurface(card: Card, modifier: Modifier = Modifier) {
    val art = remember(card.art, card.issuer, card.nickname) { CardArts.resolve(card) }
    if (art == null) {
        Box(modifier.cardBackground(card.color, card.id))
    } else {
        ArtSurface(art, modifier)
    }
}

/**
 * One card art drawn with crisp, native-DPI vector geometry in [drawWithCache] so physical bank
 * motifs (Edge+ split curves, Regalia metallic bands, Amazon Pay brushed steel, Sapphiro facets,
 * Axis globe/swirls) look authentic and razor-sharp without blurry bitmap artifacts.
 */
@Composable
fun ArtSurface(art: CardArt, modifier: Modifier = Modifier, @Suppress("UNUSED_PARAMETER") blur: Boolean = true) {
    Box(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val bg = artBackground(art, w, h)
            onDrawBehind {
                drawRect(bg)
                drawMotif(art, w, h)
            }
        },
    )
}

private fun artBackground(art: CardArt, w: Float, h: Float): Brush {
    val stops = art.colors.map { Color(it) }
    return if (stops.size == 1) Brush.linearGradient(listOf(stops[0], stops[0]))
    else Brush.linearGradient(stops, Offset.Zero, if (art.horizontal) Offset(w, 0f) else Offset(w, h))
}

private fun c(v: Long, a: Float = 1f) = Color(v).copy(alpha = a)

private fun DrawScope.drawMotif(art: CardArt, w: Float, h: Float) {
    val a = art.accent
    val a0 = a.getOrElse(0) { 0xFFFFFFFF }
    val a1 = a.getOrElse(1) { a0 }
    when (art.motif) {
        Motif.NONE -> Unit

        Motif.SHEEN -> {
            val p = Path().apply {
                moveTo(-0.06f * w, 0.74f * h)
                cubicTo(0.25f * w, 0.45f * h, 0.53f * w, 0.84f * h, 1.06f * w, 0.35f * h)
                lineTo(1.06f * w, 0.55f * h)
                cubicTo(0.56f * w, 0.99f * h, 0.28f * w, 0.6f * h, -0.06f * w, 0.94f * h)
                close()
            }
            drawPath(p, Brush.horizontalGradient(listOf(c(a0, 0f), c(a0, 0.35f), c(a0, 0f))))
            val p2 = Path().apply {
                moveTo(-0.06f * w, 0.35f * h)
                cubicTo(0.37f * w, 0.1f * h, 0.62f * w, 0.6f * h, 1.06f * w, 0.2f * h)
                lineTo(1.06f * w, 0.27f * h)
                cubicTo(0.62f * w, 0.7f * h, 0.34f * w, 0.2f * h, -0.06f * w, 0.45f * h)
                close()
            }
            drawPath(p2, Brush.horizontalGradient(listOf(c(a0, 0f), c(a0, 0.16f), c(a0, 0f))))
        }

        Motif.GLOW -> {
            val center = Offset(art.x * w, art.y * h)
            drawRect(Brush.radialGradient(listOf(c(a0, 0.85f), c(a1, 0.45f), c(a1, 0f)), center, 0.9f * w))
        }

        Motif.RAYS -> {
            listOf(Triple(0.44f, 0.72f, 0.22f), Triple(0.6f, 0.84f, 0.16f), Triple(0.72f, 0.94f, 0.12f)).forEach { (bot, top, alpha) ->
                val p = Path().apply {
                    moveTo(bot * w, h); lineTo(top * w, 0f); lineTo((top + 0.06f) * w, 0f); lineTo((bot + 0.09f) * w, h); close()
                }
                drawPath(p, c(a0, alpha))
            }
        }

        Motif.CHEVRON -> {
            val cx = 0.54f * w
            val p = Path().apply {
                moveTo(0.76f * w, 0.1f * h); lineTo(cx - 0.04f * w, 0.5f * h); lineTo(0.76f * w, 0.9f * h)
                lineTo(0.82f * w, 0.9f * h); lineTo(cx + 0.02f * w, 0.5f * h); lineTo(0.82f * w, 0.1f * h); close()
            }
            drawPath(p, c(a0, 0.45f))
        }

        Motif.ARROW -> {
            val p = Path().apply {
                moveTo(0.42f * w, 0.18f * h); lineTo(0.66f * w, 0.5f * h); lineTo(0.42f * w, 0.82f * h)
                lineTo(0.5f * w, 0.82f * h); lineTo(0.74f * w, 0.5f * h); lineTo(0.5f * w, 0.18f * h); close()
            }
            drawPath(p, c(a0, 0.9f))
        }

        Motif.PYRAMID -> {
            val p = Path().apply { moveTo(0.67f * w, 0.09f * h); lineTo(w, h); lineTo(0.34f * w, h); close() }
            drawPath(p, Brush.linearGradient(listOf(c(a0), c(a1)), Offset(0.5f * w, 0f), Offset(w, h)))
            val shade = Path().apply { moveTo(0.67f * w, 0.09f * h); lineTo(0.78f * w, h); lineTo(0.34f * w, h); close() }
            drawPath(shade, Color.Black.copy(alpha = 0.18f))
        }

        Motif.RIBBON -> {
            val cols = a.map { c(it, 0.9f) }.ifEmpty { listOf(Color.White) }
            val brush = if (cols.size == 1) Brush.horizontalGradient(listOf(cols[0], cols[0])) else Brush.linearGradient(cols, Offset(0.15f * w, h), Offset(0.85f * w, 0f))
            val p = Path().apply {
                moveTo(0.19f * w, 0.84f * h)
                cubicTo(0.12f * w, 0.45f * h, 0.38f * w, 0.15f * h, 0.66f * w, 0.25f * h)
                cubicTo(0.84f * w, 0.32f * h, 0.9f * w, 0.6f * h, 0.78f * w, 0.79f * h)
                cubicTo(0.69f * w, 0.6f * h, 0.5f * w, 0.45f * h, 0.34f * w, 0.6f * h)
                cubicTo(0.27f * w, 0.67f * h, 0.22f * w, 0.77f * h, 0.19f * w, 0.84f * h)
                close()
            }
            drawPath(p, brush)
        }

        Motif.STREAK -> {
            val beam = Brush.horizontalGradient(listOf(c(a0, 0f), c(a0, 0.8f), c(a0, 0.1f)))
            drawPath(Path().apply { moveTo(0f, 0.35f * h); lineTo(w, 0.05f * h); lineTo(w, 0.11f * h); lineTo(0f, 0.39f * h); close() }, beam)
            drawPath(Path().apply { moveTo(0f, 0.42f * h); lineTo(w, 0.17f * h); lineTo(w, 0.19f * h); lineTo(0f, 0.43f * h); close() }, beam, alpha = 0.5f)
        }

        Motif.ORB, Motif.GLOBE -> {
            val center = Offset(art.x * w, art.y * h)
            val r = 0.4f * h
            drawCircle(Brush.radialGradient(listOf(c(a0), c(a1)), Offset(center.x - r * 0.3f, center.y - r * 0.3f), r * 1.4f), r, center)
            if (art.motif == Motif.GLOBE) {
                val step = r / 8f
                var yy = center.y - r
                while (yy < center.y + r) {
                    var xx = center.x - r
                    while (xx < center.x + r) {
                        if ((xx - center.x) * (xx - center.x) + (yy - center.y) * (yy - center.y) < r * r) {
                            drawCircle(Color.Black.copy(alpha = 0.35f), step * 0.22f, Offset(xx, yy))
                        }
                        xx += step
                    }
                    yy += step
                }
            }
        }

        Motif.DOTS -> {
            val step = w / 40f
            val cx = 0.62f * w; val cy = 0.5f * h; val rx = 0.47f * w; val ry = 0.4f * h
            var yy = step / 2
            while (yy < h) {
                var xx = step / 2
                while (xx < w) {
                    val dx = (xx - cx) / rx; val dy = (yy - cy) / ry
                    if (dx * dx + dy * dy < 1f) drawCircle(c(a0, 0.14f), step * 0.13f, Offset(xx, yy))
                    xx += step
                }
                yy += step
            }
        }

        Motif.FACETS -> {
            val pts = listOf(
                0f to 0f, 0.28f to 0.3f, 0f to 0.6f, 0.56f to 0f, 0.62f to 0.45f, 1f to 0.15f, 1f to 0.7f,
                0.38f to 0.8f, 0.19f to 1f, 0f to 1f, 0.81f to 1f, 1f to 1f, 1f to 0f, 0.36f to 0.22f,
            ).map { Offset(it.first * w, it.second * h) }
            val tris = listOf(
                intArrayOf(0, 1, 2), intArrayOf(1, 3, 4), intArrayOf(2, 1, 7), intArrayOf(4, 5, 6), intArrayOf(7, 4, 10),
                intArrayOf(3, 12, 5), intArrayOf(2, 7, 8), intArrayOf(4, 6, 10), intArrayOf(1, 4, 7), intArrayOf(0, 3, 13),
                intArrayOf(8, 9, 2), intArrayOf(10, 6, 11),
            )
            val shades = listOf(0.12f, -0.08f, 0.06f, 0.16f, -0.12f, 0.08f, -0.05f, -0.1f, 0.1f, 0.04f, 0.02f, -0.06f)
            tris.forEachIndexed { i, t ->
                val p = Path().apply { moveTo(pts[t[0]].x, pts[t[0]].y); lineTo(pts[t[1]].x, pts[t[1]].y); lineTo(pts[t[2]].x, pts[t[2]].y); close() }
                val s = shades[i]
                drawPath(p, if (s > 0) Color.White.copy(alpha = s) else Color.Black.copy(alpha = -s))
            }
        }

        Motif.PIXELS -> {
            val cell = 0.045f * w
            val gap = 0.011f * w
            val brush = Brush.linearGradient(listOf(c(a0), c(a1)), Offset(0f, 0f), Offset(0.45f * w, h))
            val rnd = java.util.Random(art.id.hashCode().toLong())
            for (row in 0 until 7) for (col in 0 until 6) {
                if (rnd.nextFloat() < 0.5f) {
                    val x = 0.075f * w + col * (cell + gap)
                    val y = 0.2f * h + row * (cell + gap)
                    drawRect(brush, Offset(x, y), Size(cell, cell))
                }
            }
        }

        Motif.SWIRL -> {
            val p = Path().apply {
                moveTo(0.12f * w, 0.74f * h)
                cubicTo(0.28f * w, 0.3f * h, 0.53f * w, 0.2f * h, 0.81f * w, 0.3f * h)
                cubicTo(0.66f * w, 0.35f * h, 0.5f * w, 0.54f * h, 0.44f * w, 0.84f * h)
                cubicTo(0.37f * w, 0.69f * h, 0.25f * w, 0.69f * h, 0.12f * w, 0.74f * h)
                close()
            }
            drawPath(p, Brush.linearGradient(listOf(c(a0, 0.95f), c(a1, 0.9f)), Offset(0.1f * w, 0f), Offset(0.8f * w, h)))
            val s = Path().apply { moveTo(0.22f * w, 0.79f * h); cubicTo(0.38f * w, 0.5f * h, 0.56f * w, 0.4f * h, 0.75f * w, 0.42f * h) }
            drawPath(s, c(a0, 0.35f), style = Stroke(0.006f * w, cap = StrokeCap.Round))
        }

        Motif.BAND -> {
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, c(a0, 0.0f), c(a0, 0.9f), c(a1)), 0.45f * w, w))
            val p = Path().apply { moveTo(0.69f * w, 0f); lineTo(0.81f * w, 0f); lineTo(0.62f * w, h); lineTo(0.53f * w, h); close() }
            drawPath(p, c(a1, 0.18f))
        }

        Motif.LINES -> {
            val step = w / 22f
            rotate(35f, Offset(w / 2, h / 2)) {
                var x = -w
                while (x < 2 * w) {
                    drawLine(c(a0, 0.12f), Offset(x, -h), Offset(x, 2 * h), strokeWidth = step * 0.14f)
                    x += step
                }
            }
        }

        Motif.BRUSHED -> {
            val rnd = java.util.Random(art.id.hashCode().toLong())
            var y = 0f
            while (y < h) {
                drawLine(c(a0, 0.03f + rnd.nextFloat() * 0.05f), Offset(0f, y), Offset(w, y), strokeWidth = h / 180f)
                y += h / 70f
            }
            drawRect(Brush.linearGradient(listOf(c(a0, 0f), c(a0, 0.1f), c(a0, 0f)), Offset(0.2f * w, 0f), Offset(0.8f * w, h)))
        }

        Motif.RIPPLES -> {
            val center = Offset(art.x * w, art.y * h)
            for (i in 1..9) {
                val r = i * 0.075f * w
                drawCircle(c(a0, 0.55f - i * 0.05f), r, center, style = Stroke(0.008f * w))
            }
        }

        Motif.RINGS -> {
            drawCircle(c(a0, 0.85f), 0.2f * h * 1.5f, Offset(0.62f * w, 0.55f * h), style = Stroke(0.09f * h))
            drawCircle(c(a0, 0.7f), 0.16f * h * 1.5f, Offset(0.86f * w, 0.42f * h), style = Stroke(0.08f * h))
        }

        Motif.SPARKLE -> {
            val rnd = java.util.Random(art.id.hashCode().toLong())
            repeat(140) {
                val x = rnd.nextFloat() * w
                val y = rnd.nextFloat() * h
                val bias = x / w // denser and brighter toward the right
                if (rnd.nextFloat() < 0.35f + bias * 0.6f) drawCircle(c(a0, 0.25f + bias * 0.6f), w * (0.002f + rnd.nextFloat() * 0.004f), Offset(x, y))
            }
        }

        Motif.MOSAIC -> {
            val cols = a.ifEmpty { listOf(0xFFFFFFFF) }
            val rnd = java.util.Random(art.id.hashCode().toLong())
            val cell = 0.11f * w
            var i = 0
            var y = 0.08f * h
            while (y < h) {
                var x = 0.52f * w
                while (x < w) {
                    if (rnd.nextFloat() < 0.6f) drawRoundRectSafe(c(cols[i++ % cols.size], 0.55f), Offset(x, y), Size(cell * 0.86f, cell * 0.86f), cell * 0.18f)
                    x += cell
                }
                y += cell
            }
        }

        Motif.SPLIT -> {
            drawPath(Path().apply { moveTo(0f, h); lineTo(w, 0.2f * h); lineTo(w, h); close() }, c(a0, 0.25f))
        }

        Motif.HALF_SPLIT -> {
            // Iconic Jupiter Edge+ CSB Bank vertical-curve split: translucent/dark left + vibrant saffron right.
            val splitPath = Path().apply {
                moveTo(0.56f * w, 0f)
                lineTo(w, 0f)
                lineTo(w, h)
                lineTo(0.46f * w, h)
                close()
            }
            drawPath(splitPath, Brush.linearGradient(listOf(c(a0), c(a1)), Offset(0.45f * w, 0f), Offset(w, h)))
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.24f), Color.Transparent), Offset(0.82f * w, 0.28f * h), 0.55f * w), 0.55f * w, Offset(0.82f * w, 0.28f * h))
        }

        Motif.ARC -> {
            val p = Path().apply {
                moveTo(0f, 0f); lineTo(0.41f * w, 0f)
                cubicTo(0.28f * w, 0.35f * h, 0.34f * w, 0.69f * h, 0.53f * w, h)
                lineTo(0f, h); close()
            }
            drawPath(p, Brush.linearGradient(listOf(c(a0, 0.9f), c(a1, 0.9f)), Offset.Zero, Offset(0.5f * w, h)))
        }

        Motif.CROSS -> {
            val sw = 0.075f * w
            drawLine(c(a0), Offset(0.58f * w, 0.15f * h), Offset(0.88f * w, 0.85f * h), sw, StrokeCap.Round)
            drawLine(c(a1), Offset(0.88f * w, 0.15f * h), Offset(0.58f * w, 0.85f * h), sw, StrokeCap.Round)
        }

        Motif.WAVES -> {
            for (i in 0 until 14) {
                val off = i * 0.018f * h
                val p = Path().apply {
                    moveTo(0f, 0.7f * h + off)
                    cubicTo(0.3f * w, 0.2f * h + off, 0.55f * w, 0.95f * h - off, w, 0.35f * h + off)
                }
                drawPath(p, c(a0, 0.5f - i * 0.03f), style = Stroke(0.004f * w))
            }
        }
    }
}

private fun DrawScope.drawRoundRectSafe(color: Color, topLeft: Offset, size: Size, radius: Float) =
    drawRoundRect(color, topLeft, size, androidx.compose.ui.geometry.CornerRadius(radius, radius))

/** Signature visual accent color for a card (e.g. Amazon Orange, Slice Violet, Regalia Gold). */
fun cardAccentColor(card: Card): Color {
    val art = CardArts.resolve(card)
    val candidates = buildList {
        art?.accent?.forEach { add(Color(it)) }
        art?.colors?.forEach { add(Color(it)) }
        add(Color(card.color))
    }
    return candidates.firstOrNull { it.luminance() in 0.06f..0.88f } ?: Color(card.color)
}

/** Miniature replica of the physical card art with tiny gold chip for instant visual recognition. */
@Composable
fun MiniCardThumb(
    card: Card,
    modifier: Modifier = Modifier,
    width: Dp = 28.dp,
    cornerRadius: Dp = 5.dp,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val ink = remember(card.art, card.issuer, card.nickname, card.color) { cardInkFor(card) }
    Box(
        modifier
            .width(width)
            .aspectRatio(CARD_RATIO)
            .clip(shape)
            .border(0.6.dp, Color.White.copy(alpha = 0.28f), shape),
    ) {
        CardSurface(card, Modifier.matchParentSize())
        // Subtle metallic EMV chip dot on left
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = width * 0.14f)
                .size(width = width * 0.20f, height = width * 0.14f)
                .clip(RoundedCornerShape(1.5.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFF6E7B4), Color(0xFFCBA656)))),
        )
        // Subtle card number dots on bottom-right
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = width * 0.12f, bottom = width * 0.11f)
                .size(width = width * 0.24f, height = 2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(ink.copy(alpha = 0.72f)),
        )
    }
}
