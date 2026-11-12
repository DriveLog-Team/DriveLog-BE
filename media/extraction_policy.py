"""Validate the vehicle's registered post-trip extraction receipt and road review."""


def require_extraction(spec):
    receipt = spec.get("extraction") or {}
    if (spec.get("audioProcessingConsent") is not True or receipt.get("gear") != "P"
            or receipt.get("stationary") is not True):
        raise ValueError("Park and confirm extraction before processing")
    occupants = receipt.get("occupants")
    count = receipt.get("occupantCount")
    if type(count) is not int or not isinstance(occupants, list) or not occupants or len(occupants) != count:
        raise ValueError("Incomplete occupant confirmation")
    for occupant in occupants:
        if (not isinstance(occupant, dict) or occupant.get("allowed") is not True
                or occupant.get("ageBand") not in ("14plus", "under14")
                or (occupant["ageBand"] == "under14" and occupant.get("guardianAllowed") is not True)):
            raise ValueError("Missing occupant or guardian confirmation")


def reviewed_intervals(spec):
    review = spec.get("roadReview") or {}
    if review.get("complete") is not True:
        raise ValueError("Road footage review is pending")
    for name in ("nearPedestrian", "blur"):
        intervals = review.get(name)
        if not isinstance(intervals, list):
            raise ValueError("Expected reviewed intervals for both road cameras")
        for pair in intervals:
            if (not isinstance(pair, list) or len(pair) != 2 or any(type(t) is not int for t in pair)
                    or not 0 <= pair[0] < pair[1]):
                raise ValueError("Invalid trip-relative road review interval")
    return review


def overlapping(start, end, spans):
    return [(max(start, a), min(end, b)) for a, b in spans if a < end and b > start]
