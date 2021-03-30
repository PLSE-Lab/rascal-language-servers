/**
 * Copyright (c) 2018, Davy Landman, SWAT.engineering BV, 2020 Jurgen J. Vinju, NWO-I CWI All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 * disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.rascalmpl.vscode.lsp.rascal;

import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import com.google.common.io.CharStreams;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensDelta;
import org.eclipse.lsp4j.SemanticTokensDeltaParams;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensRangeParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.rascalmpl.parser.gtd.exception.ParseError;
import org.rascalmpl.uri.URIResolverRegistry;
import org.rascalmpl.values.parsetrees.ITree;
import org.rascalmpl.vscode.lsp.IBaseTextDocumentService;
import org.rascalmpl.vscode.lsp.TextDocumentState;
import org.rascalmpl.vscode.lsp.rascal.model.FileFacts;
import org.rascalmpl.vscode.lsp.terminal.ITerminalIDEServer.LanguageParameter;
import org.rascalmpl.vscode.lsp.util.Diagnostics;
import org.rascalmpl.vscode.lsp.util.Outline;
import org.rascalmpl.vscode.lsp.util.SemanticTokenizer;
import org.rascalmpl.vscode.lsp.util.locations.ColumnMaps;
import org.rascalmpl.vscode.lsp.util.locations.Locations;

import io.usethesource.vallang.ISourceLocation;

public class RascalTextDocumentService implements IBaseTextDocumentService, LanguageClientAware {
    private static final Logger logger = LogManager.getLogger(RascalTextDocumentService.class);
    private final ExecutorService ownExecuter;
    private final RascalLanguageServices rascalServices;

    private final SemanticTokenizer tokenizer = new SemanticTokenizer();
    private @MonotonicNonNull LanguageClient client;

    private final Map<ISourceLocation, TextDocumentState> documents;
    private final ColumnMaps columns;
    private final FileFacts facts;

    public RascalTextDocumentService(RascalLanguageServices rascal, ExecutorService exec) {
        this.ownExecuter = exec;
        this.documents = new ConcurrentHashMap<>();
        this.rascalServices = rascal;
        this.columns = new ColumnMaps(this::getContents);
        this.facts = new FileFacts(ownExecuter, rascal, columns);
    }

    private String getContents(ISourceLocation file) {
        file = file.top();
        TextDocumentState ideState = documents.get(file);
        if (ideState != null) {
            return ideState.getCurrentContent();
        }
        try (Reader src = URIResolverRegistry.getInstance().getCharacterReader(file)) {
            return CharStreams.toString(src);
        }
        catch (IOException e) {
            logger.error("Error opening file {} to get contents", file, e);
            return "";
        }
    }

    public void initializeServerCapabilities(ServerCapabilities result) {
        result.setDefinitionProvider(true);
        result.setTextDocumentSync(TextDocumentSyncKind.Full);
        result.setDocumentSymbolProvider(true);
        result.setSemanticTokensProvider(tokenizer.options());
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
        facts.setClient(client);
    }

    // LSP interface methods

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        logger.debug("Open file: {}", params.getTextDocument());
        TextDocumentState file = open(params.getTextDocument());
        handleParsingErrors(file);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        logger.trace("Change contents: {}", params.getTextDocument());
        updateContents(params.getTextDocument(), last(params.getContentChanges()).getText());
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        logger.debug("Close: {}", params.getTextDocument());
        if (documents.remove(Locations.toLoc(params.getTextDocument())) == null) {
            throw new ResponseErrorException(new ResponseError(ResponseErrorCode.InternalError,
                "Unknown file: " + Locations.toLoc(params.getTextDocument()), params));
        }
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        logger.debug("Save: {}", params.getTextDocument());
        // on save we don't get new file contents, that comes in via change
        // but we do trigger the type checker on save
        facts.invalidate(Locations.toLoc(params.getTextDocument()));
    }

    private TextDocumentState updateContents(TextDocumentIdentifier doc, String newContents) {
        TextDocumentState file = getFile(doc);
        logger.trace("New contents for {}", doc);
        handleParsingErrors(file, file.update(newContents));
        return file;
    }

    private void handleParsingErrors(TextDocumentState file, CompletableFuture<ITree> futureTree) {
        futureTree.handle((tree, excp) -> {
            Diagnostic newParseError = null;
            if (excp != null && excp instanceof CompletionException) {
                excp = excp.getCause();
            }
            if (excp instanceof ParseError) {
                newParseError = Diagnostics.translateDiagnostic((ParseError)excp, columns);
            }
            else if (excp != null) {
                logger.error("Parsing crashed", excp);
                newParseError = new Diagnostic(
                    new Range(new Position(0,0), new Position(0,1)),
                    "Parsing failed: " + excp.getMessage(),
                    DiagnosticSeverity.Error,
                    "Rascal Parser");
            }
            logger.trace("Finished parsing tree, reporting new parse error: {} for: {}", newParseError, file.getLocation());
            facts.reportParseErrors(file.getLocation(),
                newParseError == null ? Collections.emptyList() : Collections.singletonList(newParseError));
            return null;
        });
    }

    private void handleParsingErrors(TextDocumentState file) {
        handleParsingErrors(file,file.getCurrentTreeAsync());
    }


    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        logger.debug("Definition: {} at {}", params.getTextDocument(), params.getPosition());

        return facts.getSummary(Locations.toLoc(params.getTextDocument()))
            .thenApply(s -> s == null ? Collections.<Location>emptyList() : s.getDefinition(params.getPosition()))
            .thenApply(Either::forLeft)
            ;
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>>
        documentSymbol(DocumentSymbolParams params) {
        logger.debug("Outline/documentSymbols: {}", params.getTextDocument());
        TextDocumentState file = getFile(params.getTextDocument());
        return file.getCurrentTreeAsync()
            .handle((t, r) -> (t == null ? (file.getMostRecentTree()) : t))
            .thenCompose(tr -> rascalServices.getOutline(tr).get())
            .thenApply(c -> Outline.buildOutline(c, columns.get(file.getLocation())))
            ;
    }

    // Private utility methods

    private static <T> T last(List<T> l) {
        return l.get(l.size() - 1);
    }

    private TextDocumentState open(TextDocumentItem doc) {
        return documents.computeIfAbsent(Locations.toLoc(doc),
            l -> new TextDocumentState((loc, input) -> rascalServices.parseSourceFile(loc, input), l, doc.getText()));
    }

    private TextDocumentState getFile(TextDocumentIdentifier doc) {
        return getFile(Locations.toLoc(doc));
    }

    private TextDocumentState getFile(ISourceLocation loc) {
        TextDocumentState file = documents.get(loc);
        if (file == null) {
            throw new ResponseErrorException(new ResponseError(-1, "Unknown file: " + loc, loc));
        }
        return file;
    }

    public void shutdown() {
        ownExecuter.shutdown();
    }

    private CompletableFuture<SemanticTokens> getSemanticTokens(TextDocumentIdentifier doc) {
        return getFile(doc).getCurrentTreeAsync()
                .thenApplyAsync(tokenizer::semanticTokensFull, ownExecuter)
                .exceptionally(e -> {
                    logger.error("Tokenization failed", e);
                    return new SemanticTokens(Collections.emptyList());
                })
                .whenComplete((r, e) ->
                    logger.trace("Semantic tokens success, reporting {} tokens back", r == null ? 0 : r.getData().size())
                );
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        logger.debug("semanticTokensFull: {}", params.getTextDocument());
        return getSemanticTokens(params.getTextDocument());
    }

    @Override
    public CompletableFuture<Either<SemanticTokens, SemanticTokensDelta>> semanticTokensFullDelta(
            SemanticTokensDeltaParams params) {
        logger.debug("semanticTokensFullDelta: {}", params.getTextDocument());
        return getSemanticTokens(params.getTextDocument()).thenApply(Either::forLeft);
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensRange(SemanticTokensRangeParams params) {
        logger.debug("semanticTokensRange: {}", params.getTextDocument());
        return getSemanticTokens(params.getTextDocument());
    }

    @Override
    public void registerLanguage(LanguageParameter lang) {
        throw new UnsupportedOperationException("registering language is a feature of the language parametric server, not of the Rascal server");
    }
}
