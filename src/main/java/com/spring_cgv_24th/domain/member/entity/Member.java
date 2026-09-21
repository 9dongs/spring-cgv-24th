package com.spring_cgv_24th.domain.member.entity;

import com.spring_cgv_24th.domain.member.enums.MemberRole;
import com.spring_cgv_24th.global.entity.BaseCreatedEntity;
import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

@Getter
@Entity
@Table(name = "member", uniqueConstraints = {
        @UniqueConstraint(name = "uk_member_email", columnNames = "email")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long id;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "password_hash", nullable = false, length = 255)
    @ColumnDefault("'!DISABLED!'")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    @ColumnDefault("'USER'")
    private MemberRole role;

    @Builder
    public Member(String email, String name, String passwordHash, MemberRole role) {
        this.email = email;
        this.name = name;
        this.passwordHash = passwordHash;
        this.role = role == null ? MemberRole.USER : role;
    }
}
