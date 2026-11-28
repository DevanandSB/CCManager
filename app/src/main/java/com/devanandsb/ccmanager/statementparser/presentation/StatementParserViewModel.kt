package com.devanandsb.ccmanager.statementparser.presentation

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devanandsb.ccmanager.statementparser.data.StatementParserRepositoryImpl
import com.devanandsb.ccmanager.statementparser.domain.model.ParseOutcome
import com.devanandsb.ccmanager.statementparser.domain.model.ParsedStatementResult
import com.devanandsb.ccmanager.statementparser.domain.repository.StatementParserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PasswordPromptState(
    val uri: Uri,
    val hint: String,
    val incorrectAttempt: Boolean,
)

data class StatementParserUiState(
    val isLoading: Boolean = false,
    val selectedFileName: String? = null,
    val passwordPrompt: PasswordPromptState? = null,
    val result: ParsedStatementResult? = null,
    val errorMessage: String? = null,
)

class StatementParserViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val repository: StatementParserRepository = StatementParserRepositoryImpl(application)

    private val _uiState = MutableStateFlow(StatementParserUiState())
    val uiState: StateFlow<StatementParserUiState> = _uiState.asStateFlow()

    fun onPdfSelected(uri: Uri, displayName: String? = null) {
        _uiState.update {
            it.copy(
                selectedFileName = displayName ?: uri.lastPathSegment ?: "statement.pdf",
                errorMessage = null,
                passwordPrompt = null,
            )
        }
        runParse(uri = uri, password = null)
    }

    fun submitPassword(password: String) {
        val pendingUri = _uiState.value.passwordPrompt?.uri ?: return
        _uiState.update { it.copy(passwordPrompt = null) }
        runParse(uri = pendingUri, password = password.trim())
    }

    fun dismissPasswordPrompt() {
        _uiState.update { it.copy(passwordPrompt = null, isLoading = false) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun runParse(uri: Uri, password: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val outcome = repository.parseStatement(uri, password)) {
                is ParseOutcome.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            passwordPrompt = null,
                            result = outcome.result,
                            errorMessage = null,
                        )
                    }
                }

                is ParseOutcome.PasswordRequired -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            passwordPrompt = PasswordPromptState(
                                uri = uri,
                                hint = outcome.hint,
                                incorrectAttempt = outcome.incorrectAttempt,
                            ),
                        )
                    }
                }

                is ParseOutcome.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            passwordPrompt = null,
                            errorMessage = outcome.message,
                        )
                    }
                }
            }
        }
    }
}
