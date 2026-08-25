// DELETE /account — plan step 1.15. Requires an explicit confirmation, not a bare
// call: the client must echo back its own ownerId.
import {
  AdminDeleteUserCommand,
  CognitoIdentityProviderClient,
} from "@aws-sdk/client-cognito-identity-provider";
import { ApiError } from "@archivist/core/errors";
import { deleteOwnerData } from "@archivist/core/repo/account";
import { idpPtrPk, ptrSk } from "@archivist/core/keys";
import { ok, parseJsonBody } from "../http";
import type { ApiRequest, RouteHandler } from "../http";

interface DeleteAccountBody {
  /** Must equal the caller's own ownerId — a deliberate "type it to confirm". */
  confirmOwnerId: string;
}

let cognitoClient: CognitoIdentityProviderClient | undefined;
function cognito(): CognitoIdentityProviderClient {
  if (!cognitoClient) cognitoClient = new CognitoIdentityProviderClient({});
  return cognitoClient;
}

/** Cognito's internal username for a federated sign-in is `<ProviderName>_<sub>`;
 * for a native Cognito sign-in it's the sub itself. Matches `provider_name =
 * "Google"` in cognito.tf. */
function cognitoUsername(issuer: string, subject: string): string {
  if (issuer === "cognito") return subject;
  const providerName = issuer.charAt(0).toUpperCase() + issuer.slice(1);
  return `${providerName}_${subject}`;
}

export const deleteAccount: RouteHandler = async (req: ApiRequest) => {
  const { ownerId, userId } = req.auth!;
  const identity = req.identity!;
  const body = parseJsonBody<DeleteAccountBody>(req);

  if (body.confirmOwnerId !== ownerId) {
    throw ApiError.validation("confirmOwnerId must match your own ownerId");
  }

  const result = await deleteOwnerData(ownerId, userId, [
    { pk: idpPtrPk(identity.issuer, identity.subject), sk: ptrSk() },
  ]);

  const userPoolId = process.env["USER_POOL_ID"];
  if (userPoolId) {
    await cognito().send(
      new AdminDeleteUserCommand({
        UserPoolId: userPoolId,
        Username: cognitoUsername(identity.issuer, identity.subject),
      }),
    );
  }

  return ok(result);
};
