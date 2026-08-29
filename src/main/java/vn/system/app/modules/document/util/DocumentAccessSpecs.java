package vn.system.app.modules.document.util;

import java.util.Set;

import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.document.domain.Document;
import vn.system.app.modules.document.domain.DocumentAccess;
import vn.system.app.modules.procedure.enums.ProcedureType;
import vn.system.app.modules.user.domain.User;

/**
 * Predicate dùng chung để danh sách văn bản (Document) luôn tôn trọng
 * excludedUsers/excludedDepartments và văn bản CONFIDENTIAL, bất kể được
 * truy vấn từ controller/module nào.
 */
public final class DocumentAccessSpecs {

    private DocumentAccessSpecs() {
    }

    public static Specification<Document> notExcluded() {
        return (root, query, cb) -> {
            String currentUserId = SecurityUtil.getCurrentUserId().orElse("");
            UserScopeContext.UserScope scope = UserScopeContext.get();
            return notExcludedPredicate(root, query, cb, currentUserId, scope != null ? scope.departmentIds() : null);
        };
    }

    public static Predicate notExcludedPredicate(
            Root<Document> root,
            CriteriaQuery<?> query,
            CriteriaBuilder cb,
            String currentUserId,
            Set<Long> departmentIds) {

        Subquery<Integer> excludedUserSubquery = query.subquery(Integer.class);
        Root<Document> excludedUserRoot = excludedUserSubquery.from(Document.class);
        Join<Document, User> excludedUserJoin = excludedUserRoot.join("excludedUsers", JoinType.INNER);
        excludedUserSubquery.select(cb.literal(1));
        excludedUserSubquery.where(
                cb.equal(excludedUserRoot.get("id"), root.get("id")),
                cb.equal(excludedUserJoin.get("id"), currentUserId));

        Predicate notExcludedUser = cb.not(cb.exists(excludedUserSubquery));
        if (departmentIds == null || departmentIds.isEmpty()) {
            return notExcludedUser;
        }

        Subquery<Integer> excludedDepartmentSubquery = query.subquery(Integer.class);
        Root<Document> excludedDepartmentRoot = excludedDepartmentSubquery.from(Document.class);
        Join<Document, Department> excludedDepartmentJoin = excludedDepartmentRoot.join("excludedDepartments", JoinType.INNER);
        excludedDepartmentSubquery.select(cb.literal(1));
        excludedDepartmentSubquery.where(
                cb.equal(excludedDepartmentRoot.get("id"), root.get("id")),
                excludedDepartmentJoin.get("id").in(departmentIds));

        return cb.and(notExcludedUser, cb.not(cb.exists(excludedDepartmentSubquery)));
    }

    /**
     * Văn bản CONFIDENTIAL chỉ hiện với người trong DocumentAccess — dùng cho scope company-level
     * trở xuống (admin/super-admin đã bypass toàn bộ check trước khi spec này được áp).
     */
    public static Specification<Document> confidentialGuard() {
        return (root, query, cb) -> {
            String currentUserId = SecurityUtil.getCurrentUserId().orElse("");

            Subquery<Integer> accessSubquery = query.subquery(Integer.class);
            Root<DocumentAccess> accessRoot = accessSubquery.from(DocumentAccess.class);
            accessSubquery.select(cb.literal(1));
            accessSubquery.where(
                    cb.equal(accessRoot.get("document"), root),
                    cb.equal(accessRoot.get("userId"), currentUserId));

            Predicate notConfidential = cb.notEqual(root.get("procedureType"), ProcedureType.CONFIDENTIAL);
            Predicate inAccessList = cb.exists(accessSubquery);
            return cb.or(notConfidential, inAccessList);
        };
    }
}
