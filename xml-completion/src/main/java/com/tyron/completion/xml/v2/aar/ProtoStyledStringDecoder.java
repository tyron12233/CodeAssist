package com.tyron.completion.xml.v2.aar;

import com.android.aapt.Resources;
import com.android.utils.XmlUtils;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Static methods for converting {@link Resources.StyledString} proto message back to the original XML string.
 */
class ProtoStyledStringDecoder {
  /**
   * Decodes the given {@link Resources.StyledString} proto message to obtain the original XML string.
   *
   * @param styledStringMsg the proto message to decode
   * @return the original XML string
   */
  @NotNull
  public static String getRawXmlValue(@NotNull Resources.StyledString styledStringMsg) {
    String text = styledStringMsg.getValue();
    StringBuilder xmlValue = new StringBuilder(text.length() * 2);
    List<Resources.StyledString.Span> spanList = styledStringMsg.getSpanList();
    List<Resources.StyledString.Span> spanStack = new ArrayList<>(spanList.size());

    int offset = 0;
    for (int i = 0; i <= styledStringMsg.getSpanCount(); i++) {
      int oldOffset = offset;
      Resources.StyledString.Span spanMsg;
      if (i < styledStringMsg.getSpanCount()) {
        spanMsg = styledStringMsg.getSpan(i);
        offset = spanMsg.getFirstChar();
      } else {
        spanMsg = null;
        offset = text.length();
      }
      // Check if there are any tags that need to be closed.
      while (!spanStack.isEmpty() && spanStack.get(spanStack.size() - 1).getLastChar() < offset) {
        Resources.StyledString.Span span = spanStack.remove(spanStack.size() - 1);
        int spanEnd = span.getLastChar() + 1;
        if (spanEnd > oldOffset) {
          XmlUtils.appendXmlTextValue(xmlValue, text, oldOffset, spanEnd);
          oldOffset = spanEnd;
        }
        String tagText = span.getTag();
        int tagEnd = indexOfOrEnd(tagText, ';', 0);
        // Write the closing tag.
        xmlValue.append("</").append(tagText, 0, tagEnd).append('>');
      }
      if (offset >= oldOffset) {
        // Copy text between tags.
        XmlUtils.appendXmlTextValue(xmlValue, text, oldOffset, offset);
        // Start a new tag.
        if (spanMsg != null) {
          String tagText = spanMsg.getTag();
          int pos = indexOfOrEnd(tagText, ';', 0);
          if (pos != 0) {
            spanStack.add(spanMsg);
            xmlValue.append('<').append(tagText, 0, pos);
            while (pos < tagText.length()) {
              pos++;
              int nextPos = indexOfOrEnd(tagText, ';', pos);
              int nameEnd = tagText.indexOf('=', pos);
              if (nameEnd > pos && nameEnd < nextPos) {
                xmlValue.append(' ');
                xmlValue.append(tagText, pos, nameEnd + 1);
                xmlValue.append('"');
                // Attribute values in the proto message are not escaped. Append with escaping.
                XmlUtils.appendXmlAttributeValue(xmlValue, tagText, nameEnd + 1, nextPos);
                xmlValue.append('"');
              }
              pos = nextPos;
            }
            xmlValue.append('>');
          }
        }
      }
    }
    return xmlValue.toString();
  }

  private static int indexOfOrEnd(@NotNull String str, char ch, int fromIndex) {
    int index = str.indexOf(ch, fromIndex);
    return index >= 0 ? index : str.length();
  }

  /** Do not instantiate. All methods are static. */
  private ProtoStyledStringDecoder() {}
}