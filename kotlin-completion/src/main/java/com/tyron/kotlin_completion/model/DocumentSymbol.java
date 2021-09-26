package com.tyron.kotlin_completion.model;

import com.tyron.completion.model.Range;

import java.util.List;

public class DocumentSymbol {

    private String name;

    private SymbolKind kind;

    private Range range;

    private Range selectionRange;

    private String detail;

    private List<SymbolTag> tags;

    private List<DocumentSymbol> children;

    public DocumentSymbol() {
    }

    public DocumentSymbol(final String name, final SymbolKind kind, final Range range, final Range selectionRange) {
        this.name = name;
        this.kind = kind;
        this.range = range;
        this.selectionRange = selectionRange;
    }

    public DocumentSymbol(final String name, final SymbolKind kind, final Range range, final Range selectionRange, final String detail) {
        this(name, kind, range, selectionRange);
        this.detail = detail;
    }

    public DocumentSymbol(final String name, final SymbolKind kind, final Range range, final Range selectionRange, final String detail, final List<DocumentSymbol> children) {
        this(name, kind, range, selectionRange);
        this.detail = detail;
        this.children = children;
    }

    public String getName() {
        return this.name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public SymbolKind getKind() {
        return this.kind;
    }

    public void setKind(SymbolKind kind) {
        this.kind = kind;
    }

    public Range getRange() {
        return this.range;
    }

    public void setRange(Range range) {
        this.range = range;
    }

    public Range getSelectionRange() {
        return this.selectionRange;
    }

    public void setSelectionRange(Range range) {
        this.selectionRange = range;
    }

    public String getDetail() {
        return this.detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public List<SymbolTag> getTags() {
        return this.tags;
    }

    public void setTags(List<SymbolTag> tags) {
        this.tags = tags;
    }

    public List<DocumentSymbol> getChildren() {
        return this.children;
    }

    public void setChildren(final List<DocumentSymbol> children) {
        this.children = children;
    }

    @Override
    public String toString() {
        return this.name;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        DocumentSymbol other = (DocumentSymbol) obj;
        if (this.name == null) {
            if (other.name != null) {
                return false;
            }
        } else if (!this.name.equals(other.name)) {
            return false;
        }
        if (this.kind == null) {
            if (other.kind != null) {
                return false;
            }
        } else if (!this.kind.equals(other.kind)) {
            return false;
        }
        if (this.range == null) {
            if (other.range != null) {
                return false;
            }
        } else if (!this.range.equals(other.range)) {
            return false;
        }
        if (this.selectionRange == null) {
            if (other.selectionRange != null) {
                return false;
            }
        } else if (!this.selectionRange.equals(other.selectionRange)) {
            return false;
        }
        if (this.detail == null) {
            if (other.detail != null) {
                return false;
            }
        } else if (!this.detail.equals(other.detail)) {
            return false;
        }

        if (this.tags == null) {
            if (other.tags != null) {
                return false;
            }
        } else if (!this.tags.equals(other.tags)) {
            return false;
        }

        if (this.children == null) {
            if (other.children != null) {
                return false;
            }
        } else if (!this.children.equals(other.children)) {
            return false;
        }
            return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((this.name == null) ? 0 : this.name.hashCode());
        result = prime * result + ((this.kind == null) ? 0 : this.kind.hashCode());
        result = prime * result + ((this.range == null) ? 0 : this.range.hashCode());
        result = prime * result + ((this.selectionRange == null) ? 0 : this.selectionRange.hashCode());
        result = prime * result + ((this.detail == null) ? 0 : this.detail.hashCode());
        result = prime * result + ((this.tags == null) ? 0 : this.tags.hashCode());
        return prime * result + ((this.children == null) ? 0 : this.children.hashCode());
    }
}
