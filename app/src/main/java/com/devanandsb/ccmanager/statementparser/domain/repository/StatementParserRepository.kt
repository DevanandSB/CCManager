package com.devanandsb.ccmanager.statementparser.domain.repository

import android.net.Uri
import com.devanandsb.ccmanager.statementparser.domain.model.ParseOutcome

interface StatementParserRepository {
    suspend fun parseStatement(uri: Uri, password: String? = null): ParseOutcome
}
