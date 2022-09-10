package com.tyron.completion.xml.v2.base;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;

import static com.android.SdkConstants.TAG_EAT_COMMENT;

/**
 * An {@link XmlPullParser} that keeps track of the last comment preceding an XML tag and special comments
 * that are used in the framework resource files for describing groups of "attr" resources. Here is
 * an example of an "attr" group comment:
 * <pre>
 *   &lt;!-- =========== --&gt;
 *   &lt;!-- Text styles --&gt;
 *   &lt;!-- =========== --&gt;
 *   &lt;eat-comment/&gt;
 * </pre>
 */
public class CommentTrackingXmlPullParser extends KXmlParser {
  // Used for parsing group of attributes, used heuristically to skip long comments before <eat-comment/>.
  private static final int ATTR_GROUP_MAX_CHARACTERS = 40;

  @Nullable String myLastComment;
  boolean tagEncounteredAfterComment;
  @NotNull final ArrayList<String> myAttrGroupCommentStack = new ArrayList<>(4);

  /**
   * Initializes the parser. XML namespaces are supported by default.
   */
  public CommentTrackingXmlPullParser() {
    try {
      setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
    }
    catch (XmlPullParserException e) {
      throw new Error(e); // KXmlParser is guaranteed to support FEATURE_PROCESS_NAMESPACES.
    }
  }

  /**
   * Returns the last encountered comment that is not an ASCII art.
   */
  @Nullable
  public String getLastComment() {
    return myLastComment;
  }

  /**
   * Returns the name of the current "attr" group, e.g. "Button Styles" group for "buttonStyleSmall" "attr" tag.
   */
  @Nullable
  public String getAttrGroupComment() {
    return myAttrGroupCommentStack.get(myAttrGroupCommentStack.size() - 1);
  }

  @Override
  public int nextToken() throws XmlPullParserException, IOException {
    int token = super.nextToken();
    processToken(token);
    return token;
  }

  @Override
  public int next() throws XmlPullParserException, IOException {
    throw new UnsupportedOperationException("Use nextToken() instead of next() for comment tracking to work");
  }

  private void processToken(int token) {
    switch (token) {
      case XmlPullParser.START_TAG:
        if (tagEncounteredAfterComment) {
          myLastComment = null;
        }
        tagEncounteredAfterComment = true;
        // Duplicate the last element in myAttrGroupCommentStack.
        myAttrGroupCommentStack.add(myAttrGroupCommentStack.get(myAttrGroupCommentStack.size() - 1));
        assert myAttrGroupCommentStack.size() == getDepth() + 1;

        if (TAG_EAT_COMMENT.equals(getName()) && getPrefix() == null) {
          // The framework attribute file follows a special convention where related attributes are grouped together,
          // and there is always a set of comments that indicate these sections which look like this:
          //     <!-- =========== -->
          //     <!-- Text styles -->
          //     <!-- =========== -->
          //     <eat-comment/>
          // These section headers are always immediately followed by an <eat-comment>. Not all <eat-comment/> sections are
          // actually attribute headers, some are comments. We identify these by looking at the line length; category comments
          // are short, and descriptive comments are longer.
          if (myLastComment != null && myLastComment.length() <= ATTR_GROUP_MAX_CHARACTERS && !myLastComment.startsWith("TODO:")) {
            String attrGroupComment = myLastComment;
            if (attrGroupComment.endsWith(".")) {
              attrGroupComment = attrGroupComment.substring(0, attrGroupComment.length() - 1); // Strip the trailing period.
            }
            // Replace the second to last element in myAttrGroupCommentStack.
            myAttrGroupCommentStack.set(myAttrGroupCommentStack.size() - 2, attrGroupComment);
          }
        }
        break;

      case XmlPullParser.END_TAG:
        myLastComment = null;
        myAttrGroupCommentStack.remove(myAttrGroupCommentStack.size() - 1);
        break;

      case XmlPullParser.COMMENT: {
        String commentText = getText().trim();
        if (!isEmptyOrAsciiArt(commentText)) {
          myLastComment = commentText;
          tagEncounteredAfterComment = false;
        }
        break;
      }
    }
  }

  @Override
  public void setInput(@NotNull Reader reader) throws XmlPullParserException {
    super.setInput(reader);
    myLastComment = null;
    myAttrGroupCommentStack.clear();
    myAttrGroupCommentStack.add(null);
  }

  @Override
  public void setInput(@NotNull InputStream inputStream, @Nullable String encoding) throws XmlPullParserException {
    super.setInput(inputStream, encoding);
    myLastComment = null;
    myAttrGroupCommentStack.clear();
    myAttrGroupCommentStack.add(null);
  }

  private static boolean isEmptyOrAsciiArt(@NotNull String commentText) {
    return commentText.isEmpty() || commentText.charAt(0) == '*' || commentText.charAt(0) == '=';
  }
}
