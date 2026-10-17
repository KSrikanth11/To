package com.example.recipient.model;

import java.util.List;

public record SubgroupMatch(long id, String list_subgroup_name, String list_subgroup_desc,
                            String match_type, List<String> matched_terms,
                            String match_reason, double score) { }
