package com.termux.app.filemanager;

import androidx.annotation.NonNull;

import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal token-based highlighter for code/text files: comments, strings, numbers and keywords.
 * Re-analyzes the whole document on every edit, which is acceptable for the small files this
 * editor is meant for.
 */
public class RegexHighlightLanguage extends EmptyLanguage {

    private static final Pattern TOKEN = Pattern.compile(
        "//[^\\n]*"
            + "|#[^\\n]*"
            + "|/\\*[^\\n]*?\\*/"
            + "|\"(?:[^\"\\\\\\n]|\\\\.)*\""
            + "|'(?:[^'\\\\\\n]|\\\\.)*'"
            + "|\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?)\\b"
            + "|\\b(?:if|else|elif|while|for|foreach|in|return|function|def|class|struct|enum|interface"
            + "|extends|implements|public|private|protected|static|final|abstract|void|int|long|double"
            + "|float|boolean|char|byte|short|unsigned|new|delete|this|super|import|package|using|namespace"
            + "|try|catch|finally|throw|throws|switch|case|break|continue|default|const|let|var|do|done"
            + "|then|fi|echo|export|local|source|true|false|null|None|True|False|and|or|not|begin|end)\\b");

    private final AnalyzeManager mManager = new Analyzer();

    @NonNull
    @Override
    public AnalyzeManager getAnalyzeManager() {
        return mManager;
    }

    @Override
    public void destroy() {
        super.destroy();
        mManager.destroy();
    }

    private static class Analyzer extends SimpleAnalyzeManager<Void> {

        @Override
        protected Styles analyze(StringBuilder text, Delegate<Void> delegate) {
            MappedSpans.Builder builder = new MappedSpans.Builder();
            int line = 0;
            int lineStart = 0;
            int length = text.length();
            for (int i = 0; i <= length; i++) {
                if (i == length || text.charAt(i) == '\n') {
                    if (delegate.isCancelled()) return null;
                    analyzeLine(text, lineStart, i, line, builder);
                    line++;
                    lineStart = i + 1;
                }
            }
            builder.determine(Math.max(line - 1, 0));
            builder.addNormalIfNull();
            return new Styles(builder.build());
        }

        private static void analyzeLine(CharSequence text, int start, int end, int line, MappedSpans.Builder builder) {
            builder.addIfNeeded(line, 0, EditorColorScheme.TEXT_NORMAL);
            Matcher matcher = TOKEN.matcher(text.subSequence(start, end));
            while (matcher.find()) {
                builder.addIfNeeded(line, matcher.start(), colorFor(matcher.group()));
                builder.addIfNeeded(line, matcher.end(), EditorColorScheme.TEXT_NORMAL);
            }
        }

        private static int colorFor(String token) {
            char first = token.charAt(0);
            if (first == '/' || first == '#') return EditorColorScheme.COMMENT;
            if (first == '"' || first == '\'') return EditorColorScheme.LITERAL;
            if (Character.isDigit(first)) return EditorColorScheme.LITERAL;
            return EditorColorScheme.KEYWORD;
        }
    }
}
