// Copyright 2020 The Measurement System Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#ifndef WFA_MEASUREMENT_COMMON_MACROS_H_
#define WFA_MEASUREMENT_COMMON_MACROS_H_

#define RETURN_IF_ERROR(status)        \
  do {                                 \
    absl::Status _status = (status);   \
    if (!_status.ok()) return _status; \
  } while (0)

#define ASSIGN_OR_RETURN_ERROR(lhs, rexpr, message)                          \
  WFA_MEASUREMENT_COMMON__ASSIGN_OR_RETURN_IMPL_(                            \
      WFA_MEASUREMENT_COMMON_MACROS_IMPL_CONCAT_(status_or_value, __LINE__), \
      lhs, rexpr, message)

// Internal helper.
#define WFA_MEASUREMENT_COMMON__ASSIGN_OR_RETURN_IMPL_(statusor, lhs, rexpr, \
                                                       message)              \
  auto statusor = (rexpr);                                                   \
  if (ABSL_PREDICT_FALSE(!statusor.ok())) {                                  \
    return absl::InvalidArgumentError(message);                              \
  }                                                                          \
  lhs = std::move(statusor).value()

// Internal helper for concatenating macro values.
#define WFA_MEASUREMENT_COMMON_MACROS_IMPL_CONCAT_INNER_(x, y) x##y
#define WFA_MEASUREMENT_COMMON_MACROS_IMPL_CONCAT_(x, y) \
  WFA_MEASUREMENT_COMMON_MACROS_IMPL_CONCAT_INNER_(x, y)

#endif  // WFA_MEASUREMENT_COMMON_MACROS_H_
