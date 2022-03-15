package com.tyron.builder.project.util;

import org.junit.Test;

import java.util.List;

public class PackageTrieTest {

    @Test
    public void testTrie() {
        PackageTrie trie = new PackageTrie();

        trie.add("java.lang.String");
        trie.add("java.lang.Object");

        trie.add("something.Class");
        trie.add("something.another.Class");

        List<String> names = trie.getMatchingPackages("something");
        assert names.size() == 2;
        assert names.contains("something.Class");
        assert names.contains("something.another.Class");
    }

    @Test
    public void testDeepTrie() {
        PackageTrie trie = new PackageTrie();
        trie.add("some.deep.trie.Class");
        trie.add("some.deep.trie.a.Class");
        trie.add("some.deep.trie.a.b.Class");
        trie.add("some.deep.trie.a.b.c.Class");
        trie.add("some.deep.trie.a.b.c.d.Class");
        trie.add("some.deep.trie.a.b.c.d.e.Class");
        trie.add("some.deep.trie.a.b.c.d.e.f.g.Class");

        List<String> matchingPackages = trie.getMatchingPackages("some.deep.trie");
        assert matchingPackages.size() == 7;
    }

    @Test
    public void testRemove() {
        PackageTrie trie = new PackageTrie();
        trie.add("a.b");
        trie.add("a.b.c");
        trie.remove("a.b.c");

        List<String> packages = trie.getMatchingPackages("a");
        assert packages.size() == 1;
    }
}