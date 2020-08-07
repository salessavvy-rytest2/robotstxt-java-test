// Copyright 2020 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.search.robotstxt;

import com.google.common.flogger.FluentLogger;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/** Robots.txt parser implementation. */
public class RobotsParser extends Parser {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public RobotsParser(ParseHandler parseHandler) {
    super(parseHandler);
  }

  private static boolean isWhitespace(final char ch) {
    return ch == ' ' || ch == '\t';
  }

  /**
   * Extracts substring between given indexes and trims preceding and succeeding whitespace
   * characters.
   *
   * @param string string to extract from
   * @param beginIndex the beginning index, inclusive
   * @param endIndex the ending index, exclusive
   * @return extracted substring with trimmed whitespaces
   * @throws ParseException if there are only whitespace characters between given indexes
   */
  private static String trimBounded(final String string, final int beginIndex, final int endIndex)
      throws ParseException {
    int begin = beginIndex;
    int end = endIndex;
    while (begin < endIndex && isWhitespace(string.charAt(begin))) {
      begin++;
    }
    while (end > beginIndex && isWhitespace(string.charAt(end - 1))) {
      end--;
    }
    if (begin >= end) {
      throw new ParseException();
    } else {
      return string.substring(begin, end);
    }
  }

  private static DirectiveType parseDirective(final String key) {
    if (key.equalsIgnoreCase("user-agent")) {
      return DirectiveType.USER_AGENT;
    } else {
      try {
        return DirectiveType.valueOf(key.toUpperCase());
      } catch (IllegalArgumentException e) {
        return DirectiveType.UNKNOWN;
      }
    }
  }

  private static void log(
      final Level level,
      final String message,
      final String robotsTxtBody,
      final int lineBegin,
      final int lineEnd,
      final int lineNumber) {
    logger.at(level).log(
        "%s%nAt line %d:%n%s\t", message, lineNumber, robotsTxtBody.substring(lineBegin, lineEnd));
  }

  private void parseLine(
      final String robotsTxtBody, final int lineBegin, final int lineEnd, final int lineNumber) {
    int limit = lineEnd;
    int separator = lineEnd;
    int whitespaceSeparator = lineEnd;
    boolean hasContents = false;

    for (int i = lineBegin; i < lineEnd; i++) {
      final char ch = robotsTxtBody.charAt(i);
      if (ch == '#') {
        limit = i;
        break;
      }
      if (!isWhitespace(ch)) {
        hasContents = true;
      }
      if (isWhitespace(ch) && hasContents) {
        whitespaceSeparator = i;
      }
      if (separator == lineEnd && ch == ':') {
        separator = i;
      }
    }

    if (separator == lineEnd) {
      // Google-specific optimization: some people forget the colon, so we need to
      // accept whitespace in its stead.
      if (whitespaceSeparator != lineEnd) {
        log(
            Level.INFO,
            "Assuming whitespace as a separator.",
            robotsTxtBody,
            lineBegin,
            lineEnd,
            lineNumber);
        separator = whitespaceSeparator;
      } else {
        if (hasContents) {
          log(Level.WARNING, "No separator found.", robotsTxtBody, lineBegin, lineEnd, lineNumber);
        }
        return;
      }
    }

    final String key;
    try {
      key = trimBounded(robotsTxtBody, lineBegin, separator);
    } catch (ParseException e) {
      log(Level.WARNING, "No key found.", robotsTxtBody, lineBegin, lineEnd, lineNumber);
      return;
    }
    final String value;
    try {
      // Google-specific optimization: since no search engine will process more than 2083 bytes
      // per URL all values are trimmed to fit this size.
      final byte[] untrimmedValueBytes =
          trimBounded(robotsTxtBody, separator + 1, limit).getBytes(StandardCharsets.UTF_8);

      // We decrease max size by two bytes. It is done to fit a 'rectangle' character (3 bytes)
      // if the last character is trimmed to an invalid one.
      final int maxLengthBytes = 2083 - 2;

      if (untrimmedValueBytes.length > maxLengthBytes) {
        log(
            Level.INFO,
            "Value truncated to 2083 bytes.",
            robotsTxtBody,
            lineBegin,
            lineEnd,
            lineNumber);
      }

      value =
          new String(
              untrimmedValueBytes,
              0,
              Math.min(untrimmedValueBytes.length, maxLengthBytes),
              StandardCharsets.UTF_8);
    } catch (ParseException e) {
      log(Level.WARNING, "No value found.", robotsTxtBody, lineBegin, lineEnd, lineNumber);
      return;
    }
    final DirectiveType directiveType = parseDirective(key);
    if (directiveType == DirectiveType.UNKNOWN) {
      log(Level.WARNING, "Unknown key.", robotsTxtBody, lineBegin, lineEnd, lineNumber);
    }
    parseHandler.handleDirective(directiveType, value);
  }

  @Override
  Matcher parse(String robotsTxtBody) {
    int posBegin = 0;
    int posEnd = 0;
    int lineNumber = 0;
    boolean previousWasCarriageReturn = false;

    parseHandler.handleStart();

    // Iteration over characters is preferred over utilities that split text into lines to avoid
    // having to create additional Strings and comply with line breaking defined in standard.
    for (int i = 0; i < robotsTxtBody.length(); i++) {
      final char ch = robotsTxtBody.charAt(i);

      if (ch != '\n' && ch != '\r') {
        posEnd++;
      } else {
        if (posBegin != posEnd || !previousWasCarriageReturn || ch != '\n') {
          parseLine(robotsTxtBody, posBegin, posEnd, ++lineNumber);
        }
        posBegin = posEnd = i + 1;
        previousWasCarriageReturn = ch == '\r';
      }
    }

    parseHandler.handleEnd();

    return parseHandler.compute();
  }
}
