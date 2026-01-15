package com.xianyu.autoreply.repository;

import com.xianyu.autoreply.entity.DefaultReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DefaultReplyRepository extends JpaRepository<DefaultReply, String> {
}
