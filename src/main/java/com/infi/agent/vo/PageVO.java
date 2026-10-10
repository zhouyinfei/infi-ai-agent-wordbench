package com.infi.agent.vo;

import java.util.List;

/** 分页输出。 */
public record PageVO<T>(List<T> records, long total, int page, int size) {
}
