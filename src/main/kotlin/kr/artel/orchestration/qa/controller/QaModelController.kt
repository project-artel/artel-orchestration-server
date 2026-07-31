package kr.artel.orchestration.qa.controller

import kr.artel.orchestration.qa.dto.QaModelResponse
import kr.artel.orchestration.qa.service.QaModelCatalogService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/qa-models")
class QaModelController(
    private val service: QaModelCatalogService
) {
    @GetMapping
    suspend fun list(): List<QaModelResponse> = service.list()
}
