package com.example.localai.evidence

import com.example.localai.model.EvidenceRecord
import com.example.localai.model.EvidenceStatus
import com.example.localai.model.EvidenceType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Project-level service for centralized evidence storage and querying.
 *
 * All evidence records from the pipeline are registered here.
 * Provides query methods for filtering and summarizing evidence.
 */
@Service(Service.Level.PROJECT)
class EvidenceRegistryService(private val project: Project) {

    private val records = mutableListOf<EvidenceRecord>()

    fun register(record: EvidenceRecord) {
        records.add(record)
    }

    fun registerAll(newRecords: List<EvidenceRecord>) {
        records.addAll(newRecords)
    }

    fun getAll(): List<EvidenceRecord> = records.toList()

    fun getFound(): List<EvidenceRecord> = records.filter { it.status == EvidenceStatus.FOUND }

    fun getMissing(): List<EvidenceRecord> = records.filter { it.status == EvidenceStatus.NOT_FOUND }

    fun getByType(type: EvidenceType): List<EvidenceRecord> = records.filter { it.type == type }

    fun clear() = records.clear()

    fun summary(): String {
        val found = getFound().size
        val missing = getMissing().size
        return "Evidence: $found found, $missing missing"
    }
}
