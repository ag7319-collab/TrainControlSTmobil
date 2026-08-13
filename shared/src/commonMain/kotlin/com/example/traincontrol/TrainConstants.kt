package com.example.traincontrol

object TrainConstants {
    val MERAN_LINE_ALIASES = listOf(
        "MALLES", "MALS", "SILANDRO", "SCHLANDERS", "MERANO", "MERAN",
        "MERANO MAIA", "MERAN-UNTERMAIS", "LANA-POSTAL", "LANA-BURGSTALL",
        "GARGAZZONE", "GARGAZON", "VILPIANO-NALLES", "VILPIAN-NALS",
        "TERLANO-ANDRIANO", "TERLAN-ANDRIAN", "SETTEQUERCE", "SIEBENEICH",
        "PONTE D'ADIGE", "SIGMUNDSKRON", "BOLZANO CASANOVA", "BOZEN KAISERAU",
        "BOLZANO SUD", "BOZEN SÜD"
    )

    val CATEGORY_GROUPS = mapOf(
        "Regionalverkehr RFI/SAD" to listOf(
            CategoryFilter("cat_reg", "Regionalzüge(REG)", listOf("REG", "REGIONALE", "SAD"), true),
            CategoryFilter("cat_rv", "Regionalexpress (RV)", listOf("RV", "REGIONALE VELOCE"), true),
            CategoryFilter("cat_bus", "Bus Schienenersatz", listOf("BUS"), true)
        ),
        "Fernverkehr & High-Speed" to listOf(
            CategoryFilter("cat_tn_rj", "Eurocity / Railjet / Trenord (RJ/EC)", listOf("RJ", "RAILJET", "EC", "TRENORD"), false),
            CategoryFilter("cat_fv_freccia", "Frecciarossa (Alta Velocità)", listOf("FRECCIAROSSA", "FRECCIARGENTO", "FR"), false),
            CategoryFilter("cat_fv_italo", "Italo (Alta Velocità)", listOf("ITALO"), false),
            CategoryFilter("cat_fv_ic", "Intercity (IC)", listOf("INTERCITY", "IC"), false)
        )
    )
}
