/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.infrastructure.jobs.filter;

import com.google.common.collect.Lists;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.UriInfo;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.batch.domain.BatchRequest;
import org.apache.fineract.batch.domain.BatchResponse;
import org.apache.fineract.cob.data.LoanIdAndLastClosedBusinessDate;
import org.apache.fineract.cob.service.InlineLoanCOBExecutorServiceImpl;
import org.apache.fineract.cob.service.LoanAccountLockService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.data.ApiGlobalErrorResponse;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.filters.BatchFilter;
import org.apache.fineract.infrastructure.core.filters.BatchFilterChain;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.loanaccount.domain.GLIMAccountInfoRepository;
import org.apache.fineract.portfolio.loanaccount.domain.GroupLoanIndividualMonitoringAccount;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepository;
import org.apache.fineract.portfolio.loanaccount.rescheduleloan.domain.LoanRescheduleRequestRepository;
import org.apache.fineract.useradministration.exception.UnAuthenticatedUserException;
import org.apache.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class LoanCOBApiFilter extends OncePerRequestFilter implements BatchFilter {

    private final GLIMAccountInfoRepository glimAccountInfoRepository;
    private final LoanAccountLockService loanAccountLockService;
    private final PlatformSecurityContext context;
    private final InlineLoanCOBExecutorServiceImpl inlineLoanCOBExecutorService;
    private final LoanRepository loanRepository;
    private final FineractProperties fineractProperties;

    private final LoanRescheduleRequestRepository loanRescheduleRequestRepository;

    private static final List<HttpMethod> HTTP_METHODS = List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE);

    public static final Pattern IGNORE_LOAN_PATH_PATTERN = Pattern.compile("\\/loans\\/catch-up");
    public static final Pattern LOAN_PATH_PATTERN = Pattern.compile("\\/(?:reschedule)?loans\\/(?:external-id\\/)?([^\\/\\?]+).*");

    public static final Pattern LOAN_GLIMACCOUNT_PATH_PATTERN = Pattern.compile("\\/loans\\/glimAccount\\/(\\d+).*");
    private static final Predicate<String> URL_FUNCTION = s -> LOAN_PATH_PATTERN.matcher(s).find()
            || LOAN_GLIMACCOUNT_PATH_PATTERN.matcher(s).find();
    private static final String JOB_NAME = "INLINE_LOAN_COB";

    @RequiredArgsConstructor
    @Getter
    private static class LoanIdsHardLockedException extends RuntimeException {

        private final Long loanIdFromRequest;
    }

    private static class Reject {

        private final String message;
        private final Integer statusCode;

        Reject(String message, Integer statusCode) {
            this.message = message;
            this.statusCode = statusCode;
        }

        public static Reject reject(Long loanId, int status) {
            return new Reject(ApiGlobalErrorResponse.loanIsLocked(loanId).toJson(), status);
        }

        public void toServletResponse(HttpServletResponse response) throws IOException {
            response.setStatus(statusCode);
            response.getWriter().write(message);
        }

        public BatchResponse toBatchResponse(BatchRequest request) {
            BatchResponse response = new BatchResponse();
            response.setStatusCode(statusCode);
            response.setBody(message);
            response.setHeaders(request.getHeaders());
            response.setRequestId(request.getRequestId());
            return response;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!isOnApiList(request.getPathInfo(), request.getMethod())) {
            proceed(filterChain, request, response);
        } else {
            try {
                boolean bypassUser = isBypassUser();
                if (bypassUser) {
                    proceed(filterChain, request, response);
                } else {
                    try {
                        List<Long> loanIds = calculateRelevantLoanIds(request.getPathInfo());
                        if (!loanIds.isEmpty() && (isLoanSoftLocked(loanIds) || isLoanBehind(loanIds))) {
                            executeInlineCob(loanIds);
                        }
                        proceed(filterChain, request, response);
                    } catch (LoanIdsHardLockedException e) {
                        Reject.reject(e.getLoanIdFromRequest(), HttpStatus.SC_CONFLICT).toServletResponse(response);
                    }
                }
            } catch (UnAuthenticatedUserException e) {
                Reject.reject(null, HttpStatus.SC_UNAUTHORIZED).toServletResponse(response);
            }
        }
    }

    private boolean isLoanBehind(List<Long> loanIds) {
        List<LoanIdAndLastClosedBusinessDate> loanIdAndLastClosedBusinessDates = new ArrayList<>();
        List<List<Long>> partitions = Lists.partition(loanIds, fineractProperties.getQuery().getInClauseParameterSizeLimit());
        partitions.forEach(partition -> loanIdAndLastClosedBusinessDates.addAll(loanRepository
                .findAllNonClosedLoansBehindByLoanIds(ThreadLocalContextUtil.getBusinessDateByType(BusinessDateType.COB_DATE), partition)));
        return CollectionUtils.isNotEmpty(loanIdAndLastClosedBusinessDates);
    }

    private List<Long> calculateRelevantLoanIds(String pathInfo) {

        List<Long> loanIds = getLoanIdList(pathInfo);
        if (isLoanHardLocked(loanIds)) {
            throw new LoanIdsHardLockedException(loanIds.get(0));
        } else {
            return loanIds;
        }
    }

    private List<Long> getLoanIdList(String pathInfo) {
        boolean isGlim = isGlim(pathInfo);
        Long loanIdFromRequest = getLoanId(isGlim, pathInfo);
        if (loanIdFromRequest == null) {
            return Collections.emptyList();
        }
        if (isGlim) {
            return getGlimChildLoanIds(loanIdFromRequest);
        } else {
            return Collections.singletonList(loanIdFromRequest);
        }
    }

    private void executeInlineCob(List<Long> loanIds) {
        inlineLoanCOBExecutorService.execute(loanIds, JOB_NAME);
    }

    private boolean isBypassUser() {
        return context.authenticatedUser().isBypassUser();
    }

    private List<Long> getGlimChildLoanIds(Long loanIdFromRequest) {
        GroupLoanIndividualMonitoringAccount glimAccount = glimAccountInfoRepository.findOneByIsAcceptingChildAndApplicationId(true,
                BigDecimal.valueOf(loanIdFromRequest));
        if (glimAccount != null) {
            return glimAccount.getChildLoan().stream().map(Loan::getId).toList();
        } else {
            return Collections.emptyList();
        }
    }

    private boolean isLoanSoftLocked(List<Long> loanIds) {
        return isLoanLocked(loanIds, false);
    }

    private boolean isLoanHardLocked(List<Long> loanIds) {
        return isLoanLocked(loanIds, true);
    }

    private boolean isLoanLocked(List<Long> loanIds, boolean isHardLock) {
        return isHardLock ? loanIds.stream().anyMatch(loanAccountLockService::isLoanHardLocked)
                : loanIds.stream().anyMatch(loanAccountLockService::isLoanSoftLocked);
    }

    private void proceed(FilterChain filterChain, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        filterChain.doFilter(request, response);
    }

    private Long getLoanId(boolean isGlim, String pathInfo) {
        if (!isGlim) {
            String id = LOAN_PATH_PATTERN.matcher(pathInfo).replaceAll("$1");
            if (isExternal(pathInfo)) {
                String externalId = id;
                return loanRepository.findIdByExternalId(new ExternalId(externalId));
            } else if (isRescheduleLoans(pathInfo)) {
                return loanRescheduleRequestRepository.getLoanIdByRescheduleRequestId(Long.valueOf(id)).orElse(null);
            } else if (StringUtils.isNumeric(id)) {
                return Long.valueOf(id);
            } else {
                return null;
            }
        } else {
            return Long.valueOf(LOAN_GLIMACCOUNT_PATH_PATTERN.matcher(pathInfo).replaceAll("$1"));
        }
    }

    private boolean isExternal(String pathInfo) {
        return LOAN_PATH_PATTERN.matcher(pathInfo).matches() && pathInfo.contains("external-id");
    }

    private boolean isRescheduleLoans(String pathInfo) {
        return LOAN_PATH_PATTERN.matcher(pathInfo).matches() && pathInfo.contains("/rescheduleloans/");
    }

    private boolean isOnApiList(String pathInfo, String method) {
        if (StringUtils.isBlank(pathInfo)) {
            return false;
        }
        return HTTP_METHODS.contains(HttpMethod.valueOf(method)) && !IGNORE_LOAN_PATH_PATTERN.matcher(pathInfo).find()
                && URL_FUNCTION.test(pathInfo);
    }

    private boolean isGlim(String pathInfo) {
        return LOAN_GLIMACCOUNT_PATH_PATTERN.matcher(pathInfo).matches();
    }

    @Override
    public BatchResponse doFilter(BatchRequest batchRequest, UriInfo uriInfo, BatchFilterChain chain) {
        if (!isOnApiList("/" + batchRequest.getRelativeUrl(), batchRequest.getMethod())) {
            return chain.serviceCall(batchRequest, uriInfo);
        } else {
            try {
                boolean bypassUser = isBypassUser();
                if (bypassUser) {
                    return chain.serviceCall(batchRequest, uriInfo);
                } else {
                    try {
                        List<Long> result = calculateRelevantLoanIds("/" + batchRequest.getRelativeUrl());
                        if (!result.isEmpty() && (isLoanSoftLocked(result) || isLoanBehind(result))) {
                            executeInlineCob(result);
                        }
                        return chain.serviceCall(batchRequest, uriInfo);
                    } catch (LoanIdsHardLockedException e) {
                        return Reject.reject(e.getLoanIdFromRequest(), HttpStatus.SC_CONFLICT).toBatchResponse(batchRequest);
                    }
                }
            } catch (UnAuthenticatedUserException e) {
                return Reject.reject(null, HttpStatus.SC_UNAUTHORIZED).toBatchResponse(batchRequest);
            }
        }
    }
}
