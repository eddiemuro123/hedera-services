pragma solidity ^0.5.3;

contract TokenDispenser {
  address constant TOKEN_BALANCE_LIB = address(0x0000000000000000000000000000000000000888);
  address constant TOKEN_TRANSFER_LIB = address(0x0000000000000000000000000000000000000777);

  address tokenType;

  constructor(address _tokenType) public payable {
    tokenType = _tokenType;
  }  

  function() external payable {}

  function dispense() public payable {
    (bool balanceSuccess, bytes memory balanceData) = TOKEN_BALANCE_LIB.delegatecall(abi.encodePacked(tokenType));
    require(balanceSuccess);
    uint availTokens = uintFrom(balanceData);

    uint tokensRequested = msg.value;
    require(availTokens >= tokensRequested);

    bytes memory transferArgs = abi.encodePacked(msg.sender, tokenType, msg.value);  
    (bool transferSuccess, bytes memory transferData) = TOKEN_TRANSFER_LIB.delegatecall(transferArgs);
    require(transferSuccess);
  }

  function uintFrom(bytes memory _bytes) internal pure returns (uint) {
    uint ans;

    assembly {
        ans := mload(add(add(_bytes, 0x20), 0))
    }

    return ans;
  }
}
