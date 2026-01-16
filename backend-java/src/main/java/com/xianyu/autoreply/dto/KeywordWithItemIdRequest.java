package com.xianyu.autoreply.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class KeywordWithItemIdRequest {
    private List<Map<String, Object>> keywords;
}
