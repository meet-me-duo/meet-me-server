package com.meetme.server.submission.domain.dsl

/** Closed publication vocabulary, not geocoding, venue selection or evidence that a route is feasible. */
object ApprovedPublicPlaceNames {
    const val VERSION = "approved-public-place-names-1"

    val policy =
        PublicPlaceNamePolicy(
            listOf(
                "서울역",
                "용산역",
                "청량리역",
                "영등포역",
                "광명역",
                "수원역",
                "대전역",
                "동대구역",
                "부산역",
                "강남역",
                "홍대입구역",
                "서울대입구역",
                "신도림역",
                "사당역",
                "교대역",
                "건대입구역",
                "잠실역",
                "시청역",
                "종로3가역",
                "을지로입구역",
                "동대문역",
                "왕십리역",
                "공덕역",
                "합정역",
                "신촌역",
                "고속터미널역",
                "남부터미널역",
                "혜화역",
                "성수역",
                "여의도역",
                "당산역",
                "이수역",
            ).associateWith { it } +
                mapOf(
                    "서울대입구" to "서울대입구역",
                    "홍대입구" to "홍대입구역",
                    "건대입구" to "건대입구역",
                ),
        )
}
