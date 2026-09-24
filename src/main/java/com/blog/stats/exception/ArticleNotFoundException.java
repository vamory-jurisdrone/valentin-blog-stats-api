package com.blog.stats.exception;

public class ArticleNotFoundException extends RuntimeException {

    public ArticleNotFoundException(Long id) {
        super("article " + id + " not found");
    }
}
